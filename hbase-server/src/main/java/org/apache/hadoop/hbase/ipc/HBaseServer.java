/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.ipc;

import static org.apache.hadoop.fs.CommonConfigurationKeys.HADOOP_SECURITY_AUTHORIZATION;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.IpcProtocol;
import org.apache.hadoop.hbase.codec.Codec;
import org.apache.hadoop.hbase.exceptions.CallerDisconnectedException;
import org.apache.hadoop.hbase.exceptions.DoNotRetryIOException;
import org.apache.hadoop.hbase.exceptions.RegionMovedException;
import org.apache.hadoop.hbase.exceptions.ServerNotRunningYetException;
import org.apache.hadoop.hbase.io.ByteBufferOutputStream;
import org.apache.hadoop.hbase.monitoring.MonitoredRPCHandler;
import org.apache.hadoop.hbase.monitoring.TaskMonitor;
import org.apache.hadoop.hbase.protobuf.generated.RPCProtos.CellBlockMeta;
import org.apache.hadoop.hbase.protobuf.generated.RPCProtos.ConnectionHeader;
import org.apache.hadoop.hbase.protobuf.generated.RPCProtos.ExceptionResponse;
import org.apache.hadoop.hbase.protobuf.generated.RPCProtos.RequestHeader;
import org.apache.hadoop.hbase.protobuf.generated.RPCProtos.ResponseHeader;
import org.apache.hadoop.hbase.protobuf.generated.RPCProtos.UserInformation;
import org.apache.hadoop.hbase.security.HBaseSaslRpcServer;
import org.apache.hadoop.hbase.security.AuthMethod;
import org.apache.hadoop.hbase.security.HBaseSaslRpcServer.SaslDigestCallbackHandler;
import org.apache.hadoop.hbase.security.HBaseSaslRpcServer.SaslGssCallbackHandler;
import org.apache.hadoop.hbase.security.SaslStatus;
import org.apache.hadoop.hbase.security.SaslUtil;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.UserGroupInformation.AuthenticationMethod;
import org.apache.hadoop.security.authorize.AuthorizationException;
import org.apache.hadoop.security.authorize.ProxyUsers;
import org.apache.hadoop.security.authorize.ServiceAuthorizationManager;
import org.apache.hadoop.security.token.SecretManager;
import org.apache.hadoop.security.token.SecretManager.InvalidToken;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.StringUtils;
import org.cliffc.high_scale_lib.Counter;
import org.cloudera.htrace.Sampler;
import org.cloudera.htrace.Span;
import org.cloudera.htrace.Trace;
import org.cloudera.htrace.TraceInfo;
import org.cloudera.htrace.impl.NullSpan;

import com.google.common.base.Function;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Message;
import com.google.protobuf.Message.Builder;
import com.google.protobuf.TextFormat;
// Uses Writables doing sasl

/** A client for an IPC service.  IPC calls take a single Protobuf message as a
 * parameter, and return a single Protobuf message as their value.  A service runs on
 * a port and is defined by a parameter class and a value class.
 *
 *
 * <p>Copied local so can fix HBASE-900.
 *
 * @see HBaseClient
 */
@InterfaceAudience.Private
public abstract class HBaseServer implements RpcServer {
  public static final Log LOG = LogFactory.getLog("org.apache.hadoop.ipc.HBaseServer");
  private final boolean authorize;
  protected boolean isSecurityEnabled;

  public static final byte CURRENT_VERSION = 0;

  /**
   * How many calls/handler are allowed in the queue.
   */
  private static final int DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER = 10;

  /**
   * The maximum size that we can hold in the IPC queue
   */
  private static final int DEFAULT_MAX_CALLQUEUE_SIZE =
    1024 * 1024 * 1024;

  static final int BUFFER_INITIAL_SIZE = 1024;

  private static final String WARN_DELAYED_CALLS =
      "hbase.ipc.warn.delayedrpc.number";

  private static final int DEFAULT_WARN_DELAYED_CALLS = 1000;

  private final int warnDelayedCalls;

  private AtomicInteger delayedCalls;
  private final IPCUtil ipcUtil;

  private static final String AUTH_FAILED_FOR = "Auth failed for ";
  private static final String AUTH_SUCCESSFUL_FOR = "Auth successful for ";
  private static final Log AUDITLOG =
      LogFactory.getLog("SecurityLogger."+Server.class.getName());
  protected SecretManager<TokenIdentifier> secretManager;
  protected ServiceAuthorizationManager authManager;

  protected static final ThreadLocal<RpcServer> SERVER =
    new ThreadLocal<RpcServer>();
  private volatile boolean started = false;
  private static final ReflectionCache methodCache = new ReflectionCache();

  private static final Map<String, Class<? extends IpcProtocol>> PROTOCOL_CACHE =
      new ConcurrentHashMap<String, Class<? extends IpcProtocol>>();

  @SuppressWarnings("unchecked")
  static Class<? extends IpcProtocol> getProtocolClass(
      String protocolName, Configuration conf)
  throws ClassNotFoundException {
    Class<? extends IpcProtocol> protocol =
        PROTOCOL_CACHE.get(protocolName);

    if (protocol == null) {
      protocol = (Class<? extends IpcProtocol>)
          conf.getClassByName(protocolName);
      PROTOCOL_CACHE.put(protocolName, protocol);
    }
    return protocol;
  }

  /** Returns the server instance called under or null.  May be called under
   * {@code #call(Class, RpcRequestBody, long, MonitoredRPCHandler)} implementations,
   * and under protobuf methods of parameters and return values.
   * Permits applications to access the server context.
   * @return HBaseServer
   */
  public static RpcServer get() {
    return SERVER.get();
  }

  /** This is set to Call object before Handler invokes an RPC and reset
   * after the call returns.
   */
  protected static final ThreadLocal<Call> CurCall = new ThreadLocal<Call>();

  /** Returns the remote side ip address when invoked inside an RPC
   *  Returns null incase of an error.
   *  @return InetAddress
   */
  public static InetAddress getRemoteIp() {
    Call call = CurCall.get();
    if (call != null) {
      return call.connection.socket.getInetAddress();
    }
    return null;
  }
  /** Returns remote address as a string when invoked inside an RPC.
   *  Returns null in case of an error.
   *  @return String
   */
  public static String getRemoteAddress() {
    Call call = CurCall.get();
    if (call != null) {
      return call.connection.getHostAddress();
    }
    return null;
  }

  protected String bindAddress;
  protected int port;                             // port we listen on
  private int handlerCount;                       // number of handler threads
  private int priorityHandlerCount;
  private int readThreads;                        // number of read threads
  protected int maxIdleTime;                      // the maximum idle time after
                                                  // which a client may be
                                                  // disconnected
  protected int thresholdIdleConnections;         // the number of idle
                                                  // connections after which we
                                                  // will start cleaning up idle
                                                  // connections
  int maxConnectionsToNuke;                       // the max number of
                                                  // connections to nuke
                                                  // during a cleanup

  protected MetricsHBaseServer metrics;

  protected Configuration conf;

  private int maxQueueLength;
  private int maxQueueSize;
  protected int socketSendBufferSize;
  protected final boolean tcpNoDelay;   // if T then disable Nagle's Algorithm
  protected final boolean tcpKeepAlive; // if T then use keepalives
  protected final long purgeTimeout;    // in milliseconds

  volatile protected boolean running = true;         // true while server runs
  protected BlockingQueue<Call> callQueue; // queued calls
  protected final Counter callQueueSize = new Counter();
  protected BlockingQueue<Call> priorityCallQueue;

  protected int highPriorityLevel;  // what level a high priority call is at

  protected final List<Connection> connectionList =
    Collections.synchronizedList(new LinkedList<Connection>());
  //maintain a list
  //of client connections
  private Listener listener = null;
  protected Responder responder = null;
  protected int numConnections = 0;
  private Handler[] handlers = null;
  private Handler[] priorityHandlers = null;
  /** replication related queue; */
  protected BlockingQueue<Call> replicationQueue;
  private int numOfReplicationHandlers = 0;
  private Handler[] replicationHandlers = null;

  protected HBaseRPCErrorHandler errorHandler = null;

  /**
   * A convenience method to bind to a given address and report
   * better exceptions if the address is not a valid host.
   * @param socket the socket to bind
   * @param address the address to bind to
   * @param backlog the number of connections allowed in the queue
   * @throws BindException if the address can't be bound
   * @throws UnknownHostException if the address isn't a valid host name
   * @throws IOException other random errors from bind
   */
  public static void bind(ServerSocket socket, InetSocketAddress address,
                          int backlog) throws IOException {
    try {
      socket.bind(address, backlog);
    } catch (BindException e) {
      BindException bindException =
        new BindException("Problem binding to " + address + " : " +
            e.getMessage());
      bindException.initCause(e);
      throw bindException;
    } catch (SocketException e) {
      // If they try to bind to a different host's address, give a better
      // error message.
      if ("Unresolved address".equals(e.getMessage())) {
        throw new UnknownHostException("Invalid hostname for server: " +
                                       address.getHostName());
      }
      throw e;
    }
  }

  /** A call queued for handling. */
  protected class Call implements RpcCallContext {
    protected int id;                             // the client's call id
    protected Method method;
    protected Message param;                      // the parameter passed
    // Optional cell data passed outside of protobufs.
    protected CellScanner cellScanner;
    protected Connection connection;              // connection to client
    protected long timestamp;      // the time received when response is null
                                   // the time served when response is not null
    protected ByteBuffer response;                // the response for this call
    protected boolean delayResponse;
    protected Responder responder;
    protected boolean delayReturnValue;           // if the return value should be
                                                  // set at call completion
    protected long size;                          // size of current call
    protected boolean isError;
    protected TraceInfo tinfo;

    public Call(int id, Method method, Message param, CellScanner cellScanner,
        Connection connection, Responder responder, long size, TraceInfo tinfo) {
      this.id = id;
      this.method = method;
      this.param = param;
      this.cellScanner = cellScanner;
      this.connection = connection;
      this.timestamp = System.currentTimeMillis();
      this.response = null;
      this.delayResponse = false;
      this.responder = responder;
      this.isError = false;
      this.size = size;
      this.tinfo = tinfo;
    }

    @Override
    public String toString() {
      return "callId: " + this.id + " methodName: " +
        ((this.method != null)? this.method.getName(): null) + " param: " +
        (this.param != null? TextFormat.shortDebugString(this.param): "") +
        " from " + connection.toString();
    }

    protected synchronized void setSaslTokenResponse(ByteBuffer response) {
      this.response = response;
    }

    protected synchronized void setResponse(Object m, final CellScanner cells,
        Throwable t, String errorMsg) {
      if (this.isError) return;
      if (t != null) this.isError = true;
      ByteBufferOutputStream bbos = null;
      try {
        ResponseHeader.Builder headerBuilder = ResponseHeader.newBuilder();
        // Presume it a pb Message.  Could be null.
        Message result = (Message)m;
        // Call id.
        headerBuilder.setCallId(this.id);
        if (t != null) {
          ExceptionResponse.Builder exceptionBuilder = ExceptionResponse.newBuilder();
          exceptionBuilder.setExceptionClassName(t.getClass().getName());
          exceptionBuilder.setStackTrace(errorMsg);
          exceptionBuilder.setDoNotRetry(t instanceof DoNotRetryIOException);
          if (t instanceof RegionMovedException) {
            // Special casing for this exception.  This is only one carrying a payload.
            // Do this instead of build a generic system for allowing exceptions carry
            // any kind of payload.
            RegionMovedException rme = (RegionMovedException)t;
            exceptionBuilder.setHostname(rme.getHostname());
            exceptionBuilder.setPort(rme.getPort());
          }
          // Set the exception as the result of the method invocation.
          headerBuilder.setException(exceptionBuilder.build());
        }
        ByteBuffer cellBlock =
          ipcUtil.buildCellBlock(this.connection.codec, this.connection.compressionCodec, cells);
        if (cellBlock != null) {
          CellBlockMeta.Builder cellBlockBuilder = CellBlockMeta.newBuilder();
          // Presumes the cellBlock bytebuffer has been flipped so limit has total size in it.
          cellBlockBuilder.setLength(cellBlock.limit());
          headerBuilder.setCellBlockMeta(cellBlockBuilder.build());
        }
        Message header = headerBuilder.build();
        bbos = IPCUtil.write(header, result, cellBlock);
        if (connection.useWrap) {
          wrapWithSasl(bbos);
        }
        if (LOG.isDebugEnabled()) {
          LOG.debug("Header " + TextFormat.shortDebugString(header) +
            ", result " + (result != null? TextFormat.shortDebugString(result): "null"));
        }
      } catch (IOException e) {
        LOG.warn("Exception while creating response " + e);
      }
      ByteBuffer bb = null;
      if (bbos != null) {
        // TODO: If SASL, maybe buffer already been flipped and written?
        bb = bbos.getByteBuffer();
        bb.position(0);
      }
      this.response = bb;
    }

    private void wrapWithSasl(ByteBufferOutputStream response)
    throws IOException {
      if (connection.useSasl) {
        // getByteBuffer calls flip()
        ByteBuffer buf = response.getByteBuffer();
        byte[] token;
        // synchronization may be needed since there can be multiple Handler
        // threads using saslServer to wrap responses.
        synchronized (connection.saslServer) {
          token = connection.saslServer.wrap(buf.array(),
              buf.arrayOffset(), buf.remaining());
        }
        if (LOG.isDebugEnabled())
          LOG.debug("Adding saslServer wrapped token of size " + token.length
              + " as call response.");
        buf.clear();
        DataOutputStream saslOut = new DataOutputStream(response);
        saslOut.writeInt(token.length);
        saslOut.write(token, 0, token.length);
      }
    }

    @Override
    public synchronized void endDelay(Object result) throws IOException {
      assert this.delayResponse;
      assert this.delayReturnValue || result == null;
      this.delayResponse = false;
      delayedCalls.decrementAndGet();
      if (this.delayReturnValue) {
        this.setResponse(result, null, null, null);
      }
      this.responder.doRespond(this);
    }

    @Override
    public synchronized void endDelay() throws IOException {
      this.endDelay(null);
    }

    @Override
    public synchronized void startDelay(boolean delayReturnValue) {
      assert !this.delayResponse;
      this.delayResponse = true;
      this.delayReturnValue = delayReturnValue;
      int numDelayed = delayedCalls.incrementAndGet();
      if (numDelayed > warnDelayedCalls) {
        LOG.warn("Too many delayed calls: limit " + warnDelayedCalls +
            " current " + numDelayed);
      }
    }

    @Override
    public synchronized void endDelayThrowing(Throwable t) throws IOException {
      this.setResponse(null, null, t, StringUtils.stringifyException(t));
      this.delayResponse = false;
      this.sendResponseIfReady();
    }

    @Override
    public synchronized boolean isDelayed() {
      return this.delayResponse;
    }

    @Override
    public synchronized boolean isReturnValueDelayed() {
      return this.delayReturnValue;
    }

    @Override
    public void throwExceptionIfCallerDisconnected() throws CallerDisconnectedException {
      if (!connection.channel.isOpen()) {
        long afterTime = System.currentTimeMillis() - timestamp;
        throw new CallerDisconnectedException(
            "Aborting call " + this + " after " + afterTime + " ms, since " +
            "caller disconnected");
      }
    }

    public long getSize() {
      return this.size;
    }

    /**
     * If we have a response, and delay is not set, then respond
     * immediately.  Otherwise, do not respond to client.  This is
     * called the by the RPC code in the context of the Handler thread.
     */
    public synchronized void sendResponseIfReady() throws IOException {
      if (!this.delayResponse) {
        this.responder.doRespond(this);
      }
    }
  }

  /** Listens on the socket. Creates jobs for the handler threads*/
  private class Listener extends Thread {

    private ServerSocketChannel acceptChannel = null; //the accept channel
    private Selector selector = null; //the selector that we use for the server
    private Reader[] readers = null;
    private int currentReader = 0;
    private InetSocketAddress address; //the address we bind at
    private Random rand = new Random();
    private long lastCleanupRunTime = 0; //the last time when a cleanup connec-
                                         //-tion (for idle connections) ran
    private long cleanupInterval = 10000; //the minimum interval between
                                          //two cleanup runs
    private int backlogLength = conf.getInt("ipc.server.listen.queue.size", 128);

    private ExecutorService readPool;

    public Listener() throws IOException {
      address = new InetSocketAddress(bindAddress, port);
      // Create a new server socket and set to non blocking mode
      acceptChannel = ServerSocketChannel.open();
      acceptChannel.configureBlocking(false);

      // Bind the server socket to the local host and port
      bind(acceptChannel.socket(), address, backlogLength);
      port = acceptChannel.socket().getLocalPort(); //Could be an ephemeral port
      // create a selector;
      selector= Selector.open();

      readers = new Reader[readThreads];
      readPool = Executors.newFixedThreadPool(readThreads,
        new ThreadFactoryBuilder().setNameFormat(
          "IPC Reader %d on port " + port).setDaemon(true).build());
      for (int i = 0; i < readThreads; ++i) {
        Reader reader = new Reader();
        readers[i] = reader;
        readPool.execute(reader);
      }
      LOG.info(getName() + ": started " + readThreads + " reader(s) in Listener.");

      // Register accepts on the server socket with the selector.
      acceptChannel.register(selector, SelectionKey.OP_ACCEPT);
      this.setName("IPC Server listener on " + port);
      this.setDaemon(true);
    }


    private class Reader implements Runnable {
      private volatile boolean adding = false;
      private final Selector readSelector;

      Reader() throws IOException {
        this.readSelector = Selector.open();
      }
      public void run() {
        try {
          doRunLoop();
        } finally {
          try {
            readSelector.close();
          } catch (IOException ioe) {
            LOG.error(getName() + ": error closing read selector in " + getName(), ioe);
          }
        }
      }

      private synchronized void doRunLoop() {
        while (running) {
          SelectionKey key = null;
          try {
            readSelector.select();
            while (adding) {
              this.wait(1000);
            }

            Iterator<SelectionKey> iter = readSelector.selectedKeys().iterator();
            while (iter.hasNext()) {
              key = iter.next();
              iter.remove();
              if (key.isValid()) {
                if (key.isReadable()) {
                  doRead(key);
                }
              }
              key = null;
            }
          } catch (InterruptedException e) {
            if (running) {                      // unexpected -- log it
              LOG.info(getName() + ": unexpectedly interrupted: " +
                StringUtils.stringifyException(e));
            }
          } catch (IOException ex) {
            LOG.error(getName() + ": error in Reader", ex);
          }
        }
      }

      /**
       * This gets reader into the state that waits for the new channel
       * to be registered with readSelector. If it was waiting in select()
       * the thread will be woken up, otherwise whenever select() is called
       * it will return even if there is nothing to read and wait
       * in while(adding) for finishAdd call
       */
      public void startAdd() {
        adding = true;
        readSelector.wakeup();
      }

      public synchronized SelectionKey registerChannel(SocketChannel channel)
        throws IOException {
        return channel.register(readSelector, SelectionKey.OP_READ);
      }

      public synchronized void finishAdd() {
        adding = false;
        this.notify();
      }
    }

    /** cleanup connections from connectionList. Choose a random range
     * to scan and also have a limit on the number of the connections
     * that will be cleanedup per run. The criteria for cleanup is the time
     * for which the connection was idle. If 'force' is true then all
     * connections will be looked at for the cleanup.
     * @param force all connections will be looked at for cleanup
     */
    private void cleanupConnections(boolean force) {
      if (force || numConnections > thresholdIdleConnections) {
        long currentTime = System.currentTimeMillis();
        if (!force && (currentTime - lastCleanupRunTime) < cleanupInterval) {
          return;
        }
        int start = 0;
        int end = numConnections - 1;
        if (!force) {
          start = rand.nextInt() % numConnections;
          end = rand.nextInt() % numConnections;
          int temp;
          if (end < start) {
            temp = start;
            start = end;
            end = temp;
          }
        }
        int i = start;
        int numNuked = 0;
        while (i <= end) {
          Connection c;
          synchronized (connectionList) {
            try {
              c = connectionList.get(i);
            } catch (Exception e) {return;}
          }
          if (c.timedOut(currentTime)) {
            if (LOG.isDebugEnabled())
              LOG.debug(getName() + ": disconnecting client " + c.getHostAddress());
            closeConnection(c);
            numNuked++;
            end--;
            //noinspection UnusedAssignment
            c = null;
            if (!force && numNuked == maxConnectionsToNuke) break;
          }
          else i++;
        }
        lastCleanupRunTime = System.currentTimeMillis();
      }
    }

    @Override
    public void run() {
      LOG.info(getName() + ": starting");
      SERVER.set(HBaseServer.this);

      while (running) {
        SelectionKey key = null;
        try {
          selector.select(); // FindBugs IS2_INCONSISTENT_SYNC
          Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
          while (iter.hasNext()) {
            key = iter.next();
            iter.remove();
            try {
              if (key.isValid()) {
                if (key.isAcceptable())
                  doAccept(key);
              }
            } catch (IOException ignored) {
            }
            key = null;
          }
        } catch (OutOfMemoryError e) {
          if (errorHandler != null) {
            if (errorHandler.checkOOME(e)) {
              LOG.info(getName() + ": exiting on OutOfMemoryError");
              closeCurrentConnection(key, e);
              cleanupConnections(true);
              return;
            }
          } else {
            // we can run out of memory if we have too many threads
            // log the event and sleep for a minute and give
            // some thread(s) a chance to finish
            LOG.warn(getName() + ": OutOfMemoryError in server select", e);
            closeCurrentConnection(key, e);
            cleanupConnections(true);
            try { Thread.sleep(60000); } catch (Exception ignored) {}
          }
        } catch (Exception e) {
          closeCurrentConnection(key, e);
        }
        cleanupConnections(false);
      }
      LOG.info(getName() + ": stopping");

      synchronized (this) {
        try {
          acceptChannel.close();
          selector.close();
        } catch (IOException ignored) { }

        selector= null;
        acceptChannel= null;

        // clean up all connections
        while (!connectionList.isEmpty()) {
          closeConnection(connectionList.remove(0));
        }
      }
    }

    private void closeCurrentConnection(SelectionKey key, Throwable e) {
      if (key != null) {
        Connection c = (Connection)key.attachment();
        if (c != null) {
          if (LOG.isDebugEnabled()) {
            LOG.debug(getName() + ": disconnecting client " + c.getHostAddress() +
                (e != null ? " on error " + e.getMessage() : ""));
          }
          closeConnection(c);
          key.attach(null);
        }
      }
    }

    InetSocketAddress getAddress() {
      return (InetSocketAddress)acceptChannel.socket().getLocalSocketAddress();
    }

    void doAccept(SelectionKey key) throws IOException, OutOfMemoryError {
      Connection c;
      ServerSocketChannel server = (ServerSocketChannel) key.channel();

      SocketChannel channel;
      while ((channel = server.accept()) != null) {
        channel.configureBlocking(false);
        channel.socket().setTcpNoDelay(tcpNoDelay);
        channel.socket().setKeepAlive(tcpKeepAlive);

        Reader reader = getReader();
        try {
          reader.startAdd();
          SelectionKey readKey = reader.registerChannel(channel);
          c = getConnection(channel, System.currentTimeMillis());
          readKey.attach(c);
          synchronized (connectionList) {
            connectionList.add(numConnections, c);
            numConnections++;
          }
          if (LOG.isDebugEnabled())
            LOG.debug(getName() + ": connection from " + c.toString() +
                "; # active connections: " + numConnections +
                "; # queued calls: " + callQueue.size());
        } finally {
          reader.finishAdd();
        }
      }
    }

    void doRead(SelectionKey key) throws InterruptedException {
      int count = 0;
      Connection c = (Connection)key.attachment();
      if (c == null) {
        return;
      }
      c.setLastContact(System.currentTimeMillis());
      try {
        count = c.readAndProcess();
      } catch (InterruptedException ieo) {
        throw ieo;
      } catch (Exception e) {
        LOG.warn(getName() + ": count of bytes read: " + count, e);
        count = -1; //so that the (count < 0) block is executed
      }
      if (count < 0) {
        if (LOG.isDebugEnabled()) {
          LOG.debug(getName() + ": disconnecting client " + c.getHostAddress() +
            ", because count=" + count +
            ". Number of active connections: " + numConnections);
        }
        closeConnection(c);
        // c = null;
      } else {
        c.setLastContact(System.currentTimeMillis());
      }
    }

    synchronized void doStop() {
      if (selector != null) {
        selector.wakeup();
        Thread.yield();
      }
      if (acceptChannel != null) {
        try {
          acceptChannel.socket().close();
        } catch (IOException e) {
          LOG.info(getName() + ": exception in closing listener socket. " + e);
        }
      }
      readPool.shutdownNow();
    }

    // The method that will return the next reader to work with
    // Simplistic implementation of round robin for now
    Reader getReader() {
      currentReader = (currentReader + 1) % readers.length;
      return readers[currentReader];
    }
  }

  // Sends responses of RPC back to clients.
  protected class Responder extends Thread {
    private final Selector writeSelector;
    private int pending;         // connections waiting to register

    Responder() throws IOException {
      this.setName("IPC Server Responder");
      this.setDaemon(true);
      writeSelector = Selector.open(); // create a selector
      pending = 0;
    }

    @Override
    public void run() {
      LOG.info(getName() + ": starting");
      SERVER.set(HBaseServer.this);
      try {
        doRunLoop();
      } finally {
        LOG.info(getName() + ": stopping");
        try {
          writeSelector.close();
        } catch (IOException ioe) {
          LOG.error(getName() + ": couldn't close write selector", ioe);
        }
      }
    }

    private void doRunLoop() {
      long lastPurgeTime = 0;   // last check for old calls.

      while (running) {
        try {
          waitPending();     // If a channel is being registered, wait.
          writeSelector.select(purgeTimeout);
          Iterator<SelectionKey> iter = writeSelector.selectedKeys().iterator();
          while (iter.hasNext()) {
            SelectionKey key = iter.next();
            iter.remove();
            try {
              if (key.isValid() && key.isWritable()) {
                  doAsyncWrite(key);
              }
            } catch (IOException e) {
              LOG.info(getName() + ": asyncWrite", e);
            }
          }
          long now = System.currentTimeMillis();
          if (now < lastPurgeTime + purgeTimeout) {
            continue;
          }
          lastPurgeTime = now;
          //
          // If there were some calls that have not been sent out for a
          // long time, discard them.
          //
          if (LOG.isDebugEnabled()) LOG.debug(getName() + ": checking for old call responses.");
          ArrayList<Call> calls;

          // get the list of channels from list of keys.
          synchronized (writeSelector.keys()) {
            calls = new ArrayList<Call>(writeSelector.keys().size());
            iter = writeSelector.keys().iterator();
            while (iter.hasNext()) {
              SelectionKey key = iter.next();
              Call call = (Call)key.attachment();
              if (call != null && key.channel() == call.connection.channel) {
                calls.add(call);
              }
            }
          }

          for(Call call : calls) {
            try {
              doPurge(call, now);
            } catch (IOException e) {
              LOG.warn(getName() + ": error in purging old calls " + e);
            }
          }
        } catch (OutOfMemoryError e) {
          if (errorHandler != null) {
            if (errorHandler.checkOOME(e)) {
              LOG.info(getName() + ": exiting on OutOfMemoryError");
              return;
            }
          } else {
            //
            // we can run out of memory if we have too many threads
            // log the event and sleep for a minute and give
            // some thread(s) a chance to finish
            //
            LOG.warn(getName() + ": OutOfMemoryError in server select", e);
            try { Thread.sleep(60000); } catch (Exception ignored) {}
          }
        } catch (Exception e) {
          LOG.warn(getName() + ": exception in Responder " +
                   StringUtils.stringifyException(e));
        }
      }
      LOG.info(getName() + ": stopped");
    }

    private void doAsyncWrite(SelectionKey key) throws IOException {
      Call call = (Call)key.attachment();
      if (call == null) {
        return;
      }
      if (key.channel() != call.connection.channel) {
        throw new IOException("doAsyncWrite: bad channel");
      }

      synchronized(call.connection.responseQueue) {
        if (processResponse(call.connection.responseQueue, false)) {
          try {
            key.interestOps(0);
          } catch (CancelledKeyException e) {
            /* The Listener/reader might have closed the socket.
             * We don't explicitly cancel the key, so not sure if this will
             * ever fire.
             * This warning could be removed.
             */
            LOG.warn("Exception while changing ops : " + e);
          }
        }
      }
    }

    //
    // Remove calls that have been pending in the responseQueue
    // for a long time.
    //
    private void doPurge(Call call, long now) throws IOException {
      synchronized (call.connection.responseQueue) {
        Iterator<Call> iter = call.connection.responseQueue.listIterator(0);
        while (iter.hasNext()) {
          Call nextCall = iter.next();
          if (now > nextCall.timestamp + purgeTimeout) {
            closeConnection(nextCall.connection);
            break;
          }
        }
      }
    }

    // Processes one response. Returns true if there are no more pending
    // data for this channel.
    //
    private boolean processResponse(final LinkedList<Call> responseQueue, boolean inHandler)
    throws IOException {
      boolean error = true;
      boolean done = false;       // there is more data for this channel.
      int numElements;
      Call call = null;
      try {
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (responseQueue) {
          //
          // If there are no items for this channel, then we are done
          //
          numElements = responseQueue.size();
          if (numElements == 0) {
            error = false;
            return true;              // no more data for this channel.
          }
          //
          // Extract the first call
          //
          call = responseQueue.removeFirst();
          SocketChannel channel = call.connection.channel;
          //
          // Send as much data as we can in the non-blocking fashion
          //
          int numBytes = channelWrite(channel, call.response);
          if (numBytes < 0) {
            return true;
          }
          if (!call.response.hasRemaining()) {
            call.connection.decRpcCount();
            //noinspection RedundantIfStatement
            if (numElements == 1) {    // last call fully processes.
              done = true;             // no more data for this channel.
            } else {
              done = false;            // more calls pending to be sent.
            }
            if (LOG.isDebugEnabled()) {
              LOG.debug(getName() + ": callId: " + call.id + " sent, wrote " + numBytes +
                " bytes.");
            }
          } else {
            //
            // If we were unable to write the entire response out, then
            // insert in Selector queue.
            //
            call.connection.responseQueue.addFirst(call);

            if (inHandler) {
              // set the serve time when the response has to be sent later
              call.timestamp = System.currentTimeMillis();
              if (enqueueInSelector(call))
                done = true;
            }
            if (LOG.isDebugEnabled()) {
              LOG.debug(getName() + call.toString() + " partially sent, wrote " +
                numBytes + " bytes.");
            }
          }
          error = false;              // everything went off well
        }
      } finally {
        if (error && call != null) {
          LOG.warn(getName() + call.toString() + ": output error");
          done = true;               // error. no more data for this channel.
          closeConnection(call.connection);
        }
      }
      return done;
    }

    //
    // Enqueue for background thread to send responses out later.
    //
    private boolean enqueueInSelector(Call call) throws IOException {
      boolean done = false;
      incPending();
      try {
        // Wake up the thread blocked on select, only then can the call
        // to channel.register() complete.
        SocketChannel channel = call.connection.channel;
        writeSelector.wakeup();
        channel.register(writeSelector, SelectionKey.OP_WRITE, call);
      } catch (ClosedChannelException e) {
        //It's OK.  Channel might be closed else where.
        done = true;
      } finally {
        decPending();
      }
      return done;
    }

    //
    // Enqueue a response from the application.
    //
    void doRespond(Call call) throws IOException {
      // set the serve time when the response has to be sent later
      call.timestamp = System.currentTimeMillis();

      boolean doRegister = false;
      synchronized (call.connection.responseQueue) {
        call.connection.responseQueue.addLast(call);
        if (call.connection.responseQueue.size() == 1) {
          doRegister = !processResponse(call.connection.responseQueue, false);
        }
      }
      if (doRegister) {
        enqueueInSelector(call);
      }
    }

    private synchronized void incPending() {   // call waiting to be enqueued.
      pending++;
    }

    private synchronized void decPending() { // call done enqueueing.
      pending--;
      notify();
    }

    private synchronized void waitPending() throws InterruptedException {
      while (pending > 0) {
        wait();
      }
    }
  }

  @SuppressWarnings("serial")
  public static class CallQueueTooBigException extends IOException {
    CallQueueTooBigException() {
      super();
    }
  }

  private Function<Pair<RequestHeader, Message>, Integer> qosFunction = null;

  /**
   * Gets the QOS level for this call.  If it is higher than the highPriorityLevel and there
   * are priorityHandlers available it will be processed in it's own thread set.
   *
   * @param newFunc
   */
  @Override
  public void setQosFunction(Function<Pair<RequestHeader, Message>, Integer> newFunc) {
    qosFunction = newFunc;
  }

  protected int getQosLevel(Pair<RequestHeader, Message> headerAndParam) {
    if (qosFunction == null) return 0;
    Integer res = qosFunction.apply(headerAndParam);
    return res == null? 0: res;
  }

  /** Reads calls from a connection and queues them for handling. */
  public class Connection {
    // If initial preamble with version and magic has been read or not.
    private boolean connectionPreambleRead = false;
    // If the connection header has been read or not.
    private boolean connectionHeaderRead = false;
    protected SocketChannel channel;
    private ByteBuffer data;
    private ByteBuffer dataLengthBuffer;
    protected final LinkedList<Call> responseQueue;
    private volatile int rpcCount = 0; // number of outstanding rpcs
    private long lastContact;
    private InetAddress addr;
    protected Socket socket;
    // Cache the remote host & port info so that even if the socket is
    // disconnected, we can say where it used to connect to.
    protected String hostAddress;
    protected int remotePort;
    ConnectionHeader connectionHeader;
    /**
     * Codec the client asked use.
     */
    private Codec codec;
    /**
     * Compression codec the client asked us use.
     */
    private CompressionCodec compressionCodec;
    Class<? extends IpcProtocol> protocol;
    protected UserGroupInformation user = null;
    private AuthMethod authMethod;
    private boolean saslContextEstablished;
    private boolean skipInitialSaslHandshake;
    private ByteBuffer unwrappedData;
    // When is this set?  FindBugs wants to know!  Says NP
    private ByteBuffer unwrappedDataLengthBuffer;
    boolean useSasl;
    SaslServer saslServer;
    private boolean useWrap = false;
    // Fake 'call' for failed authorization response
    private static final int AUTHROIZATION_FAILED_CALLID = -1;
    private final Call authFailedCall =
      new Call(AUTHROIZATION_FAILED_CALLID, null, null, null, this, null, 0, null);
    private ByteArrayOutputStream authFailedResponse =
        new ByteArrayOutputStream();
    // Fake 'call' for SASL context setup
    private static final int SASL_CALLID = -33;
    private final Call saslCall =
      new Call(SASL_CALLID, null, null, null, this, null, 0, null);

    public UserGroupInformation attemptingUser = null; // user name before auth

    public Connection(SocketChannel channel, long lastContact) {
      this.channel = channel;
      this.lastContact = lastContact;
      this.data = null;
      this.dataLengthBuffer = ByteBuffer.allocate(4);
      this.socket = channel.socket();
      this.addr = socket.getInetAddress();
      if (addr == null) {
        this.hostAddress = "*Unknown*";
      } else {
        this.hostAddress = addr.getHostAddress();
      }
      this.remotePort = socket.getPort();
      this.responseQueue = new LinkedList<Call>();
      if (socketSendBufferSize != 0) {
        try {
          socket.setSendBufferSize(socketSendBufferSize);
        } catch (IOException e) {
          LOG.warn("Connection: unable to set socket send buffer size to " +
                   socketSendBufferSize);
        }
      }
    }

    @Override
    public String toString() {
      return getHostAddress() + ":" + remotePort;
    }

    public String getHostAddress() {
      return hostAddress;
    }

    public InetAddress getHostInetAddress() {
      return addr;
    }

    public int getRemotePort() {
      return remotePort;
    }

    public void setLastContact(long lastContact) {
      this.lastContact = lastContact;
    }

    public long getLastContact() {
      return lastContact;
    }

    /* Return true if the connection has no outstanding rpc */
    private boolean isIdle() {
      return rpcCount == 0;
    }

    /* Decrement the outstanding RPC count */
    protected void decRpcCount() {
      rpcCount--;
    }

    /* Increment the outstanding RPC count */
    protected void incRpcCount() {
      rpcCount++;
    }

    protected boolean timedOut(long currentTime) {
      return isIdle() && currentTime - lastContact > maxIdleTime;
    }

    private UserGroupInformation getAuthorizedUgi(String authorizedId)
        throws IOException {
      if (authMethod == AuthMethod.DIGEST) {
        TokenIdentifier tokenId = HBaseSaslRpcServer.getIdentifier(authorizedId,
            secretManager);
        UserGroupInformation ugi = tokenId.getUser();
        if (ugi == null) {
          throw new AccessControlException(
              "Can't retrieve username from tokenIdentifier.");
        }
        ugi.addTokenIdentifier(tokenId);
        return ugi;
      } else {
        return UserGroupInformation.createRemoteUser(authorizedId);
      }
    }

    private void saslReadAndProcess(byte[] saslToken) throws IOException,
        InterruptedException {
      if (saslContextEstablished) {
        if (LOG.isDebugEnabled())
          LOG.debug("Have read input token of size " + saslToken.length
              + " for processing by saslServer.unwrap()");

        if (!useWrap) {
          processOneRpc(saslToken);
        } else {
          byte[] plaintextData = saslServer.unwrap(saslToken, 0,
              saslToken.length);
          processUnwrappedData(plaintextData);
        }
      } else {
        byte[] replyToken = null;
        try {
          if (saslServer == null) {
            switch (authMethod) {
            case DIGEST:
              if (secretManager == null) {
                throw new AccessControlException(
                    "Server is not configured to do DIGEST authentication.");
              }
              saslServer = Sasl.createSaslServer(AuthMethod.DIGEST
                  .getMechanismName(), null, SaslUtil.SASL_DEFAULT_REALM,
                  SaslUtil.SASL_PROPS, new SaslDigestCallbackHandler(
                      secretManager, this));
              break;
            default:
              UserGroupInformation current = UserGroupInformation
              .getCurrentUser();
              String fullName = current.getUserName();
              if (LOG.isDebugEnabled()) {
                LOG.debug("Kerberos principal name is " + fullName);
              }
              final String names[] = SaslUtil.splitKerberosName(fullName);
              if (names.length != 3) {
                throw new AccessControlException(
                    "Kerberos principal name does NOT have the expected "
                        + "hostname part: " + fullName);
              }
              current.doAs(new PrivilegedExceptionAction<Object>() {
                @Override
                public Object run() throws SaslException {
                  saslServer = Sasl.createSaslServer(AuthMethod.KERBEROS
                      .getMechanismName(), names[0], names[1],
                      SaslUtil.SASL_PROPS, new SaslGssCallbackHandler());
                  return null;
                }
              });
            }
            if (saslServer == null)
              throw new AccessControlException(
                  "Unable to find SASL server implementation for "
                      + authMethod.getMechanismName());
            if (LOG.isDebugEnabled()) {
              LOG.debug("Created SASL server with mechanism = " + authMethod.getMechanismName());
            }
          }
          if (LOG.isDebugEnabled()) {
            LOG.debug("Have read input token of size " + saslToken.length
                + " for processing by saslServer.evaluateResponse()");
          }
          replyToken = saslServer.evaluateResponse(saslToken);
        } catch (IOException e) {
          IOException sendToClient = e;
          Throwable cause = e;
          while (cause != null) {
            if (cause instanceof InvalidToken) {
              sendToClient = (InvalidToken) cause;
              break;
            }
            cause = cause.getCause();
          }
          doRawSaslReply(SaslStatus.ERROR, null, sendToClient.getClass().getName(),
              sendToClient.getLocalizedMessage());
          metrics.authenticationFailure();
          String clientIP = this.toString();
          // attempting user could be null
          AUDITLOG.warn(AUTH_FAILED_FOR + clientIP + ":" + attemptingUser);
          throw e;
        }
        if (replyToken != null) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Will send token of size " + replyToken.length
                + " from saslServer.");
          }
          doRawSaslReply(SaslStatus.SUCCESS, new BytesWritable(replyToken), null,
              null);
        }
        if (saslServer.isComplete()) {
          String qop = (String) saslServer.getNegotiatedProperty(Sasl.QOP);
          useWrap = qop != null && !"auth".equalsIgnoreCase(qop);
          user = getAuthorizedUgi(saslServer.getAuthorizationID());
          if (LOG.isDebugEnabled()) {
            LOG.debug("SASL server context established. Authenticated client: "
              + user + ". Negotiated QoP is "
              + saslServer.getNegotiatedProperty(Sasl.QOP));
          }
          metrics.authenticationSuccess();
          AUDITLOG.info(AUTH_SUCCESSFUL_FOR + user);
          saslContextEstablished = true;
        }
      }
    }
    /**
     * No protobuf encoding of raw sasl messages
     */
    private void doRawSaslReply(SaslStatus status, Writable rv,
        String errorClass, String error) throws IOException {
      //In my testing, have noticed that sasl messages are usually
      //in the ballpark of 100-200. That's why the initialcapacity is 256.
      ByteBufferOutputStream saslResponse = new ByteBufferOutputStream(256);
      DataOutputStream out = new DataOutputStream(saslResponse);
      out.writeInt(status.state); // write status
      if (status == SaslStatus.SUCCESS) {
        rv.write(out);
      } else {
        WritableUtils.writeString(out, errorClass);
        WritableUtils.writeString(out, error);
      }
      saslCall.setSaslTokenResponse(saslResponse.getByteBuffer());
      saslCall.responder = responder;
      saslCall.sendResponseIfReady();
    }

    private void disposeSasl() {
      if (saslServer != null) {
        try {
          saslServer.dispose();
          saslServer = null;
        } catch (SaslException ignored) {
        }
      }
    }

    /**
     * Read off the wire.
     * @return Returns -1 if failure (and caller will close connection) else return how many
     * bytes were read and processed
     * @throws IOException
     * @throws InterruptedException
     */
    public int readAndProcess() throws IOException, InterruptedException {
      while (true) {
        // Try and read in an int.  If new connection, the int will hold the 'HBas' HEADER.  If it
        // does, read in the rest of the connection preamble, the version and the auth method.
        // Else it will be length of the data to read (or -1 if a ping).  We catch the integer
        // length into the 4-byte this.dataLengthBuffer.
        int count;
        if (this.dataLengthBuffer.remaining() > 0) {
          count = channelRead(channel, this.dataLengthBuffer);
          if (count < 0 || this.dataLengthBuffer.remaining() > 0) {
            return count;
          }
        }
        // If we have not read the connection setup preamble, look to see if that is on the wire.
        if (!connectionPreambleRead) {
          // Check for 'HBas' magic.
          this.dataLengthBuffer.flip();
          if (!HConstants.RPC_HEADER.equals(dataLengthBuffer)) {
            return doBadPreambleHandling("Expected HEADER=" +
              Bytes.toStringBinary(HConstants.RPC_HEADER.array()) +
              " but received HEADER=" + Bytes.toStringBinary(dataLengthBuffer.array()));
          }
          // Now read the next two bytes, the version and the auth to use.
          ByteBuffer versionAndAuthBytes = ByteBuffer.allocate(2);
          count = channelRead(channel, versionAndAuthBytes);
          if (count < 0 || versionAndAuthBytes.remaining() > 0) {
            return count;
          }
          int version = versionAndAuthBytes.get(0);
          byte authbyte = versionAndAuthBytes.get(1);
          this.authMethod = AuthMethod.valueOf(authbyte);
          if (version != CURRENT_VERSION || authMethod == null) {
            return doBadPreambleHandling("serverVersion=" + CURRENT_VERSION +
              ", clientVersion=" + version + ", authMethod=" + authbyte +
              ", authSupported=" + (authMethod != null));
          }
          if (isSecurityEnabled && authMethod == AuthMethod.SIMPLE) {
            AccessControlException ae = new AccessControlException("Authentication is required");
            setupResponse(authFailedResponse, authFailedCall, ae, ae.getMessage());
            responder.doRespond(authFailedCall);
            throw ae;
          }
          if (!isSecurityEnabled && authMethod != AuthMethod.SIMPLE) {
            doRawSaslReply(SaslStatus.SUCCESS, new IntWritable(
                SaslUtil.SWITCH_TO_SIMPLE_AUTH), null, null);
            authMethod = AuthMethod.SIMPLE;
            // client has already sent the initial Sasl message and we
            // should ignore it. Both client and server should fall back
            // to simple auth from now on.
            skipInitialSaslHandshake = true;
          }
          if (authMethod != AuthMethod.SIMPLE) {
            useSasl = true;
          }
          connectionPreambleRead = true;
          // Preamble checks out. Go around again to read actual connection header.
          dataLengthBuffer.clear();
          continue;
        }
        // We have read a length and we have read the preamble.  It is either the connection header
        // or it is a request.
        if (data == null) {
          dataLengthBuffer.flip();
          int dataLength = dataLengthBuffer.getInt();
          if (dataLength == HBaseClient.PING_CALL_ID) {
            if (!useWrap) { //covers the !useSasl too
              dataLengthBuffer.clear();
              return 0;  //ping message
            }
          }
          if (dataLength < 0) {
            throw new IllegalArgumentException("Unexpected data length "
                + dataLength + "!! from " + getHostAddress());
          }
          data = ByteBuffer.allocate(dataLength);
          incRpcCount();  // Increment the rpc count
        }
        count = channelRead(channel, data);
        if (data.remaining() == 0) {
          dataLengthBuffer.clear();
          data.flip();
          if (skipInitialSaslHandshake) {
            data = null;
            skipInitialSaslHandshake = false;
            continue;
          }
          boolean headerRead = connectionHeaderRead;
          if (useSasl) {
            saslReadAndProcess(data.array());
          } else {
            processOneRpc(data.array());
          }
          this.data = null;
          if (!headerRead) {
            continue;
          }
        } else {
          // More to read still; go around again.
          if (LOG.isTraceEnabled()) LOG.trace("Continue to read rest of data " + data.remaining());
          continue;
        }
        return count;
      }
    }

    private int doBadPreambleHandling(final String errMsg) throws IOException {
      String msg = errMsg + "; cannot communicate with client at " + hostAddress + ":" + port;
      LOG.warn(msg);
      Call fakeCall = new Call(-1, null, null, null, this, responder, -1, null);
      setupResponse(null, fakeCall, new FatalConnectionException(msg), msg);
      responder.doRespond(fakeCall);
      // Returning -1 closes out the connection.
      return -1;
    }

    // Reads the connection header following version
    private void processConnectionHeader(byte[] buf) throws IOException {
      this.connectionHeader = ConnectionHeader.parseFrom(buf);
      try {
        String protocolClassName = connectionHeader.getProtocol();
        if (protocolClassName != null) {
          protocol = getProtocolClass(connectionHeader.getProtocol(), conf);
        }
      } catch (ClassNotFoundException cnfe) {
        throw new IOException("Unknown protocol: " + connectionHeader.getProtocol());
      }
      setupCellBlockCodecs(this.connectionHeader);

      UserGroupInformation protocolUser = createUser(connectionHeader);
      if (!useSasl) {
        user = protocolUser;
        if (user != null) {
          user.setAuthenticationMethod(AuthMethod.SIMPLE.authenticationMethod);
        }
      } else {
        // user is authenticated
        user.setAuthenticationMethod(authMethod.authenticationMethod);
        //Now we check if this is a proxy user case. If the protocol user is
        //different from the 'user', it is a proxy user scenario. However,
        //this is not allowed if user authenticated with DIGEST.
        if ((protocolUser != null)
            && (!protocolUser.getUserName().equals(user.getUserName()))) {
          if (authMethod == AuthMethod.DIGEST) {
            // Not allowed to doAs if token authentication is used
            throw new AccessControlException("Authenticated user (" + user
                + ") doesn't match what the client claims to be ("
                + protocolUser + ")");
          } else {
            // Effective user can be different from authenticated user
            // for simple auth or kerberos auth
            // The user is the real user. Now we create a proxy user
            UserGroupInformation realUser = user;
            user = UserGroupInformation.createProxyUser(protocolUser
                .getUserName(), realUser);
            // Now the user is a proxy user, set Authentication method Proxy.
            user.setAuthenticationMethod(AuthenticationMethod.PROXY);
          }
        }
      }
    }

    /**
     * Set up cell block codecs
     * @param header
     * @throws FatalConnectionException
     */
    private void setupCellBlockCodecs(final ConnectionHeader header)
    throws FatalConnectionException {
      // TODO: Plug in other supported decoders.
      if (!header.hasCellBlockCodecClass()) throw new FatalConnectionException("No codec");
      String className = header.getCellBlockCodecClass();
      try {
        this.codec = (Codec)Class.forName(className).newInstance();
      } catch (Exception e) {
        throw new FatalConnectionException("Unsupported codec " + className, e);
      }
      if (!header.hasCellBlockCompressorClass()) return;
      className = header.getCellBlockCompressorClass();
      try {
        this.compressionCodec = (CompressionCodec)Class.forName(className).newInstance();
      } catch (Exception e) {
        throw new FatalConnectionException("Unsupported codec " + className, e);
      }
    }

    private void processUnwrappedData(byte[] inBuf) throws IOException,
    InterruptedException {
      ReadableByteChannel ch = Channels.newChannel(new ByteArrayInputStream(
          inBuf));
      // Read all RPCs contained in the inBuf, even partial ones
      while (true) {
        int count = -1;
        if (unwrappedDataLengthBuffer.remaining() > 0) {
          count = channelRead(ch, unwrappedDataLengthBuffer);
          if (count <= 0 || unwrappedDataLengthBuffer.remaining() > 0)
            return;
        }

        if (unwrappedData == null) {
          unwrappedDataLengthBuffer.flip();
          int unwrappedDataLength = unwrappedDataLengthBuffer.getInt();

          if (unwrappedDataLength == HBaseClient.PING_CALL_ID) {
            if (LOG.isDebugEnabled())
              LOG.debug("Received ping message");
            unwrappedDataLengthBuffer.clear();
            continue; // ping message
          }
          unwrappedData = ByteBuffer.allocate(unwrappedDataLength);
        }

        count = channelRead(ch, unwrappedData);
        if (count <= 0 || unwrappedData.remaining() > 0)
          return;

        if (unwrappedData.remaining() == 0) {
          unwrappedDataLengthBuffer.clear();
          unwrappedData.flip();
          processOneRpc(unwrappedData.array());
          unwrappedData = null;
        }
      }
    }

    private void processOneRpc(byte[] buf) throws IOException,
    InterruptedException {
      if (connectionHeaderRead) {
        processRequest(buf);
      } else {
        processConnectionHeader(buf);
        this.connectionHeaderRead = true;
        if (!authorizeConnection()) {
          throw new AccessControlException("Connection from " + this
              + " for protocol " + connectionHeader.getProtocol()
              + " is unauthorized for user " + user);
        }
      }
    }

    /**
     * @param buf Has the request header and the request param and optionally encoded data buffer
     * all in this one array.
     * @throws IOException
     * @throws InterruptedException
     */
    protected void processRequest(byte[] buf) throws IOException, InterruptedException {
      long totalRequestSize = buf.length;
      int offset = 0;
      // Here we read in the header.  We avoid having pb
      // do its default 4k allocation for CodedInputStream.  We force it to use backing array.
      CodedInputStream cis = CodedInputStream.newInstance(buf, offset, buf.length);
      int headerSize = cis.readRawVarint32();
      offset = cis.getTotalBytesRead();
      RequestHeader header =
        RequestHeader.newBuilder().mergeFrom(buf, offset, headerSize).build();
      offset += headerSize;
      int id = header.getCallId();
      if (LOG.isDebugEnabled()) {
        LOG.debug("RequestHeader " + TextFormat.shortDebugString(header) +
          " totalRequestSize: " + totalRequestSize + " bytes");
      }
      // Enforcing the call queue size, this triggers a retry in the client
      // This is a bit late to be doing this check - we have already read in the total request.
      if ((totalRequestSize + callQueueSize.get()) > maxQueueSize) {
        final Call callTooBig =
          new Call(id, null, null, null, this, responder, totalRequestSize, null);
        ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
        setupResponse(responseBuffer, callTooBig, new CallQueueTooBigException(),
          "Call queue is full, is ipc.server.max.callqueue.size too small?");
        responder.doRespond(callTooBig);
        return;
      }
      Method method = null;
      Message param = null;
      CellScanner cellScanner = null;
      try {
        if (header.hasRequestParam() && header.getRequestParam()) {
          method = methodCache.getMethod(this.protocol, header.getMethodName());
          Message m = methodCache.getMethodArgType(method);
          // Check that there is a param to deserialize.
          if (m != null) {
            Builder builder = null;
            builder = m.newBuilderForType();
            // To read the varint, I need an inputstream; might as well be a CIS.
            cis = CodedInputStream.newInstance(buf, offset, buf.length);
            int paramSize = cis.readRawVarint32();
            offset += cis.getTotalBytesRead();
            if (builder != null) {
              builder.mergeFrom(buf, offset, paramSize);
              param = builder.build();
            }
            offset += paramSize;
          }
        }
        if (header.hasCellBlockMeta()) {
          cellScanner = ipcUtil.createCellScanner(this.codec, this.compressionCodec,
            buf, offset, buf.length);
        }
      } catch (Throwable t) {
        String msg = "Unable to read call parameter from client " + getHostAddress();
        LOG.warn(msg, t);
        final Call readParamsFailedCall =
          new Call(id, null, null, null, this, responder, totalRequestSize, null);
        ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
        setupResponse(responseBuffer, readParamsFailedCall, t,
          msg + "; " + t.getMessage());
        responder.doRespond(readParamsFailedCall);
        return;
      }

      Call call = null;
      if (header.hasTraceInfo()) {
        call = new Call(id, method, param, cellScanner, this, responder, totalRequestSize,
          new TraceInfo(header.getTraceInfo().getTraceId(), header.getTraceInfo().getParentId()));
      } else {
        call = new Call(id, method, param, cellScanner, this, responder, totalRequestSize, null);
      }
      callQueueSize.add(totalRequestSize);
      Pair<RequestHeader, Message> headerAndParam = new Pair<RequestHeader, Message>(header, param);
      if (priorityCallQueue != null && getQosLevel(headerAndParam) > highPriorityLevel) {
        priorityCallQueue.put(call);
      } else if (replicationQueue != null &&
          getQosLevel(headerAndParam) == HConstants.REPLICATION_QOS) {
        replicationQueue.put(call);
      } else {
        callQueue.put(call);              // queue the call; maybe blocked here
      }
    }

    private boolean authorizeConnection() throws IOException {
      try {
        // If auth method is DIGEST, the token was obtained by the
        // real user for the effective user, therefore not required to
        // authorize real user. doAs is allowed only for simple or kerberos
        // authentication
        if (user != null && user.getRealUser() != null
            && (authMethod != AuthMethod.DIGEST)) {
          ProxyUsers.authorize(user, this.getHostAddress(), conf);
        }
        authorize(user, connectionHeader, getHostInetAddress());
        if (LOG.isDebugEnabled()) {
          LOG.debug("Authorized " + TextFormat.shortDebugString(connectionHeader));
        }
        metrics.authorizationSuccess();
      } catch (AuthorizationException ae) {
        LOG.debug("Connection authorization failed: " + ae.getMessage(), ae);
        metrics.authorizationFailure();
        setupResponse(authFailedResponse, authFailedCall, ae, ae.getMessage());
        responder.doRespond(authFailedCall);
        return false;
      }
      return true;
    }

    protected synchronized void close() {
      disposeSasl();
      data = null;
      this.dataLengthBuffer = null;
      if (!channel.isOpen())
        return;
      try {socket.shutdownOutput();} catch(Exception ignored) {} // FindBugs DE_MIGHT_IGNORE
      if (channel.isOpen()) {
        try {channel.close();} catch(Exception ignored) {}
      }
      try {socket.close();} catch(Exception ignored) {}
    }

    private UserGroupInformation createUser(ConnectionHeader head) {
      UserGroupInformation ugi = null;

      if (!head.hasUserInfo()) {
        return null;
      }
      UserInformation userInfoProto = head.getUserInfo();
      String effectiveUser = null;
      if (userInfoProto.hasEffectiveUser()) {
        effectiveUser = userInfoProto.getEffectiveUser();
      }
      String realUser = null;
      if (userInfoProto.hasRealUser()) {
        realUser = userInfoProto.getRealUser();
      }
      if (effectiveUser != null) {
        if (realUser != null) {
          UserGroupInformation realUserUgi =
              UserGroupInformation.createRemoteUser(realUser);
          ugi = UserGroupInformation.createProxyUser(effectiveUser, realUserUgi);
        } else {
          ugi = UserGroupInformation.createRemoteUser(effectiveUser);
        }
      }
      return ugi;
    }
  }

  /** Handles queued calls . */
  private class Handler extends Thread {
    private final BlockingQueue<Call> myCallQueue;
    private MonitoredRPCHandler status;

    public Handler(final BlockingQueue<Call> cq, int instanceNumber) {
      this.myCallQueue = cq;
      this.setDaemon(true);

      String threadName = "IPC Server handler " + instanceNumber + " on " + port;
      if (cq == priorityCallQueue) {
        // this is just an amazing hack, but it works.
        threadName = "PRI " + threadName;
      } else if (cq == replicationQueue) {
        threadName = "REPL " + threadName;
      }
      this.setName(threadName);
      this.status = TaskMonitor.get().createRPCStatus(threadName);
    }

    @Override
    public void run() {
      LOG.info(getName() + ": starting");
      status.setStatus("starting");
      SERVER.set(HBaseServer.this);
      while (running) {
        try {
          status.pause("Waiting for a call");
          Call call = myCallQueue.take(); // pop the queue; maybe blocked here
          status.setStatus("Setting up call");
          status.setConnection(call.connection.getHostAddress(), call.connection.getRemotePort());
          if (LOG.isDebugEnabled()) {
            UserGroupInformation remoteUser = call.connection.user;
            LOG.debug(call.toString() + " executing as " +
              ((remoteUser == null)? "NULL principal": remoteUser.getUserName()));
          }
          Throwable errorThrowable = null;
          String error = null;
          Pair<Message, CellScanner> resultPair = null;
          CurCall.set(call);
          Span currentRequestSpan = NullSpan.getInstance();
          try {
            if (!started) {
              throw new ServerNotRunningYetException("Server is not running yet");
            }
            if (call.tinfo != null) {
              currentRequestSpan = Trace.startSpan(
                  "handling " + call.toString(), call.tinfo, Sampler.ALWAYS);
            }
            RequestContext.set(User.create(call.connection.user), getRemoteIp(),
              call.connection.protocol);

            // make the call
            resultPair = call(call.connection.protocol, call.method, call.param, call.cellScanner,
              call.timestamp, status);
          } catch (Throwable e) {
            LOG.debug(getName() + ": " + call.toString() + " error: " + e, e);
            errorThrowable = e;
            error = StringUtils.stringifyException(e);
          } finally {
            currentRequestSpan.stop();
            // Must always clear the request context to avoid leaking
            // credentials between requests.
            RequestContext.clear();
          }
          CurCall.set(null);
          callQueueSize.add(call.getSize() * -1);
          // Set the response for undelayed calls and delayed calls with
          // undelayed responses.
          if (!call.isDelayed() || !call.isReturnValueDelayed()) {
            Message param = resultPair != null? resultPair.getFirst(): null;
            CellScanner cells = resultPair != null? resultPair.getSecond(): null;
            call.setResponse(param, cells, errorThrowable, error);
          }
          call.sendResponseIfReady();
          status.markComplete("Sent response");
        } catch (InterruptedException e) {
          if (running) {                          // unexpected -- log it
            LOG.info(getName() + ": caught: " + StringUtils.stringifyException(e));
          }
        } catch (OutOfMemoryError e) {
          if (errorHandler != null) {
            if (errorHandler.checkOOME(e)) {
              LOG.info(getName() + ": exiting on OutOfMemoryError");
              return;
            }
          } else {
            // rethrow if no handler
            throw e;
          }
       } catch (ClosedChannelException cce) {
          LOG.warn(getName() + ": caught a ClosedChannelException, " +
            "this means that the server was processing a " +
            "request but the client went away. The error message was: " +
            cce.getMessage());
        } catch (Exception e) {
          LOG.warn(getName() + ": caught: " + StringUtils.stringifyException(e));
        }
      }
      LOG.info(getName() + ": exiting");
    }
  }

  /* Constructs a server listening on the named port and address.  Parameters passed must
   * be of the named class.  The <code>handlerCount</handlerCount> determines
   * the number of handler threads that will be used to process calls.
   *
   */
  protected HBaseServer(String bindAddress, int port,
                        int handlerCount,
                        int priorityHandlerCount, Configuration conf, String serverName,
                        int highPriorityLevel)
    throws IOException {
    this.bindAddress = bindAddress;
    this.conf = conf;
    this.port = port;
    this.handlerCount = handlerCount;
    this.priorityHandlerCount = priorityHandlerCount;
    this.socketSendBufferSize = 0;
    this.maxQueueLength =
      this.conf.getInt("ipc.server.max.callqueue.length",
        handlerCount * DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER);
    this.maxQueueSize =
      this.conf.getInt("ipc.server.max.callqueue.size",
        DEFAULT_MAX_CALLQUEUE_SIZE);
     this.readThreads = conf.getInt(
        "ipc.server.read.threadpool.size",
        10);
    this.callQueue  = new LinkedBlockingQueue<Call>(maxQueueLength);
    if (priorityHandlerCount > 0) {
      this.priorityCallQueue = new LinkedBlockingQueue<Call>(maxQueueLength); // TODO hack on size
    } else {
      this.priorityCallQueue = null;
    }
    this.highPriorityLevel = highPriorityLevel;
    this.maxIdleTime = 2*conf.getInt("ipc.client.connection.maxidletime", 1000);
    this.maxConnectionsToNuke = conf.getInt("ipc.client.kill.max", 10);
    this.thresholdIdleConnections = conf.getInt("ipc.client.idlethreshold", 4000);
    this.purgeTimeout = conf.getLong("ipc.client.call.purge.timeout",
                                     2 * HConstants.DEFAULT_HBASE_RPC_TIMEOUT);
    this.numOfReplicationHandlers = conf.getInt("hbase.regionserver.replication.handler.count", 3);
    if (numOfReplicationHandlers > 0) {
      this.replicationQueue = new LinkedBlockingQueue<Call>(maxQueueSize);
    }
    // Start the listener here and let it bind to the port
    listener = new Listener();
    this.port = listener.getAddress().getPort();

    this.metrics = new MetricsHBaseServer(
        serverName, new MetricsHBaseServerWrapperImpl(this));
    this.tcpNoDelay = conf.getBoolean("ipc.server.tcpnodelay", true);
    this.tcpKeepAlive = conf.getBoolean("ipc.server.tcpkeepalive", true);

    this.warnDelayedCalls = conf.getInt(WARN_DELAYED_CALLS,
                                        DEFAULT_WARN_DELAYED_CALLS);
    this.delayedCalls = new AtomicInteger(0);
    this.ipcUtil = new IPCUtil(conf);


    // Create the responder here
    responder = new Responder();
    this.authorize =
        conf.getBoolean(HADOOP_SECURITY_AUTHORIZATION, false);
    this.isSecurityEnabled = User.isHBaseSecurityEnabled(this.conf);
    if (isSecurityEnabled) {
      HBaseSaslRpcServer.init(conf);
    }
  }

  /**
   * Subclasses of HBaseServer can override this to provide their own
   * Connection implementations.
   */
  protected Connection getConnection(SocketChannel channel, long time) {
    return new Connection(channel, time);
  }

  /**
   * Setup response for the IPC Call.
   *
   * @param response buffer to serialize the response into
   * @param call {@link Call} to which we are setting up the response
   * @param status {@link Status} of the IPC call
   * @param errorClass error class, if the the call failed
   * @param error error message, if the call failed
   * @throws IOException
   */
  private void setupResponse(ByteArrayOutputStream response, Call call, Throwable t, String error)
  throws IOException {
    if (response != null) response.reset();
    call.setResponse(null, null, t, error);
  }

  protected void closeConnection(Connection connection) {
    synchronized (connectionList) {
      if (connectionList.remove(connection)) {
        numConnections--;
      }
    }
    connection.close();
  }

  Configuration getConf() {
    return conf;
  }

  /** Sets the socket buffer size used for responding to RPCs.
   * @param size send size
   */
  @Override
  public void setSocketSendBufSize(int size) { this.socketSendBufferSize = size; }

  /** Starts the service.  Must be called before any calls will be handled. */
  @Override
  public void start() {
    startThreads();
    openServer();
  }

  /**
   * Open a previously started server.
   */
  @Override
  public void openServer() {
    started = true;
  }

  /**
   * Starts the service threads but does not allow requests to be responded yet.
   * Client will get {@link ServerNotRunningYetException} instead.
   */
  @Override
  public synchronized void startThreads() {
    responder.start();
    listener.start();
    handlers = startHandlers(callQueue, handlerCount);
    priorityHandlers = startHandlers(priorityCallQueue, priorityHandlerCount);
    replicationHandlers = startHandlers(replicationQueue, numOfReplicationHandlers);
  }

  private Handler[] startHandlers(BlockingQueue<Call> queue, int numOfHandlers) {
    if (numOfHandlers <= 0) {
      return null;
    }
    Handler[] handlers = new Handler[numOfHandlers];
    for (int i = 0; i < numOfHandlers; i++) {
      handlers[i] = new Handler(queue, i);
      handlers[i].start();
    }
    return handlers;
  }

  public SecretManager<? extends TokenIdentifier> getSecretManager() {
    return this.secretManager;
  }

  @SuppressWarnings("unchecked")
  public void setSecretManager(SecretManager<? extends TokenIdentifier> secretManager) {
    this.secretManager = (SecretManager<TokenIdentifier>) secretManager;
  }

  /** Stops the service.  No new calls will be handled after this is called. */
  @Override
  public synchronized void stop() {
    LOG.info("Stopping server on " + port);
    running = false;
    stopHandlers(handlers);
    stopHandlers(priorityHandlers);
    stopHandlers(replicationHandlers);
    listener.interrupt();
    listener.doStop();
    responder.interrupt();
    notifyAll();
  }

  private void stopHandlers(Handler[] handlers) {
    if (handlers != null) {
      for (Handler handler : handlers) {
        if (handler != null) {
          handler.interrupt();
        }
      }
    }
  }

  /** Wait for the server to be stopped.
   * Does not wait for all subthreads to finish.
   *  See {@link #stop()}.
   * @throws InterruptedException e
   */
  @Override
  public synchronized void join() throws InterruptedException {
    while (running) {
      wait();
    }
  }

  /**
   * Return the socket (ip+port) on which the RPC server is listening to.
   * @return the socket (ip+port) on which the RPC server is listening to.
   */
  @Override
  public synchronized InetSocketAddress getListenerAddress() {
    return listener.getAddress();
  }

  /**
   * Set the handler for calling out of RPC for error conditions.
   * @param handler the handler implementation
   */
  @Override
  public void setErrorHandler(HBaseRPCErrorHandler handler) {
    this.errorHandler = handler;
  }

  /**
   * Returns the metrics instance for reporting RPC call statistics
   */
  public MetricsHBaseServer getMetrics() {
    return metrics;
  }

  /**
   * Authorize the incoming client connection.
   *
   * @param user client user
   * @param connection incoming connection
   * @param addr InetAddress of incoming connection
   * @throws org.apache.hadoop.security.authorize.AuthorizationException when the client isn't authorized to talk the protocol
   */
  @SuppressWarnings("static-access")
  public void authorize(UserGroupInformation user,
                        ConnectionHeader connection,
                        InetAddress addr
                        ) throws AuthorizationException {
    if (authorize) {
      Class<?> protocol = null;
      try {
        protocol = getProtocolClass(connection.getProtocol(), getConf());
      } catch (ClassNotFoundException cfne) {
        throw new AuthorizationException("Unknown protocol: " +
                                         connection.getProtocol());
      }
      authManager.authorize(user != null ? user : null,
        protocol, getConf(), addr);
    }
  }

  /**
   * When the read or write buffer size is larger than this limit, i/o will be
   * done in chunks of this size. Most RPC requests and responses would be
   * be smaller.
   */
  private static int NIO_BUFFER_LIMIT = 64 * 1024; //should not be more than 64KB.

  /**
   * This is a wrapper around {@link java.nio.channels.WritableByteChannel#write(java.nio.ByteBuffer)}.
   * If the amount of data is large, it writes to channel in smaller chunks.
   * This is to avoid jdk from creating many direct buffers as the size of
   * buffer increases. This also minimizes extra copies in NIO layer
   * as a result of multiple write operations required to write a large
   * buffer.
   *
   * @param channel writable byte channel to write to
   * @param buffer buffer to write
   * @return number of bytes written
   * @throws java.io.IOException e
   * @see java.nio.channels.WritableByteChannel#write(java.nio.ByteBuffer)
   */
  protected int channelWrite(WritableByteChannel channel,
                                    ByteBuffer buffer) throws IOException {

    int count =  (buffer.remaining() <= NIO_BUFFER_LIMIT) ?
           channel.write(buffer) : channelIO(null, channel, buffer);
    if (count > 0) {
      metrics.sentBytes(count);
    }
    return count;
  }

  /**
   * This is a wrapper around {@link java.nio.channels.ReadableByteChannel#read(java.nio.ByteBuffer)}.
   * If the amount of data is large, it writes to channel in smaller chunks.
   * This is to avoid jdk from creating many direct buffers as the size of
   * ByteBuffer increases. There should not be any performance degredation.
   *
   * @param channel writable byte channel to write on
   * @param buffer buffer to write
   * @return number of bytes written
   * @throws java.io.IOException e
   * @see java.nio.channels.ReadableByteChannel#read(java.nio.ByteBuffer)
   */
  protected int channelRead(ReadableByteChannel channel,
                                   ByteBuffer buffer) throws IOException {

    int count = (buffer.remaining() <= NIO_BUFFER_LIMIT) ?
           channel.read(buffer) : channelIO(channel, null, buffer);
    if (count > 0) {
      metrics.receivedBytes(count);
    }
    return count;
  }

  /**
   * Helper for {@link #channelRead(java.nio.channels.ReadableByteChannel, java.nio.ByteBuffer)}
   * and {@link #channelWrite(java.nio.channels.WritableByteChannel, java.nio.ByteBuffer)}. Only
   * one of readCh or writeCh should be non-null.
   *
   * @param readCh read channel
   * @param writeCh write channel
   * @param buf buffer to read or write into/out of
   * @return bytes written
   * @throws java.io.IOException e
   * @see #channelRead(java.nio.channels.ReadableByteChannel, java.nio.ByteBuffer)
   * @see #channelWrite(java.nio.channels.WritableByteChannel, java.nio.ByteBuffer)
   */
  private static int channelIO(ReadableByteChannel readCh,
                               WritableByteChannel writeCh,
                               ByteBuffer buf) throws IOException {

    int originalLimit = buf.limit();
    int initialRemaining = buf.remaining();
    int ret = 0;

    while (buf.remaining() > 0) {
      try {
        int ioSize = Math.min(buf.remaining(), NIO_BUFFER_LIMIT);
        buf.limit(buf.position() + ioSize);

        ret = (readCh == null) ? writeCh.write(buf) : readCh.read(buf);

        if (ret < ioSize) {
          break;
        }

      } finally {
        buf.limit(originalLimit);
      }
    }

    int nBytes = initialRemaining - buf.remaining();
    return (nBytes > 0) ? nBytes : ret;
  }

  /**
   * Needed for delayed calls.  We need to be able to store the current call
   * so that we can complete it later.
   * @return Call the server is currently handling.
   */
  public static RpcCallContext getCurrentCall() {
    return CurCall.get();
  }
}
