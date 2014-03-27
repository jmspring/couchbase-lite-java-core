package com.couchbase.lite.replicator;

import com.couchbase.lite.*;
import com.couchbase.lite.auth.Authorizer;
import com.couchbase.lite.auth.FacebookAuthorizer;
import com.couchbase.lite.auth.PersonaAuthorizer;
import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.internal.RevisionInternal;
import com.couchbase.lite.support.*;
import com.couchbase.lite.util.Log;
import com.couchbase.lite.util.TextUtils;
import com.couchbase.lite.util.URIUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpResponseException;
import org.apache.http.entity.mime.MultipartEntity;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.Principal; // https://github.com/couchbase/couchbase-lite-java-core/issues/40
import java.util.*;
import java.util.concurrent.*;

/**
 * A Couchbase Lite pull or push Replication between a local and a remote Database.
 */
public abstract class Replication implements NetworkReachabilityListener {

    private static int lastSessionID = 0;

    protected boolean continuous;
    protected String filterName;
    protected ScheduledExecutorService workExecutor;
    protected Database db;
    protected URL remote;
    protected String lastSequence;
    protected boolean lastSequenceChanged;
    protected Map<String, Object> remoteCheckpoint;
    protected boolean savingCheckpoint;
    protected boolean overdueForSave;
    protected boolean running;
    protected boolean active;
    protected Throwable error;
    protected String sessionID;
    protected Batcher<RevisionInternal> batcher;
    protected int asyncTaskCount;
    private int completedChangesCount;
    private int changesCount;
    protected boolean online;
    protected HttpClientFactory clientFactory;
    private List<ChangeListener> changeListeners;
    protected List<String> documentIDs;
    protected ConcurrentHashMap<Principal, Boolean> principals = new ConcurrentHashMap<Principal, Boolean>(); // https://github.com/couchbase/couchbase-lite-java-core/issues/40

    protected Map<String, Object> filterParams;
    protected ExecutorService remoteRequestExecutor;
    protected Authorizer authorizer;
    private ReplicationStatus status = ReplicationStatus.REPLICATION_STOPPED;
    protected Map<String, Object> requestHeaders;
    private int revisionsFailed;
    private ScheduledFuture retryIfReadyFuture;
    private Map<RemoteRequest, Future> requests;
    private String serverType;

    protected static final int PROCESSOR_DELAY = 500;
    protected static final int INBOX_CAPACITY = 100;
    protected static final int RETRY_DELAY = 60;
    protected static final int EXECUTOR_THREAD_POOL_SIZE = 2;


    /**
     * @exclude
     */
    public static final String BY_CHANNEL_FILTER_NAME = "sync_gateway/bychannel";

    /**
     * @exclude
     */
    public static final String CHANNELS_QUERY_PARAM = "channels";

    /**
     * @exclude
     */
    public static final String REPLICATOR_DATABASE_NAME = "_replicator";

    /**
     * Options for what metadata to include in document bodies
     */
    public enum ReplicationStatus {
        /** The replication is finished or hit a fatal error. */
        REPLICATION_STOPPED,
        /** The remote host is currently unreachable. */
        REPLICATION_OFFLINE,
        /** Continuous replication is caught up and waiting for more changes.*/
        REPLICATION_IDLE,
        /** The replication is actively transferring data. */
        REPLICATION_ACTIVE
    }


    /**
     * Private Constructor
     * @exclude
     */
    @InterfaceAudience.Private
    /* package */ Replication(Database db, URL remote, boolean continuous, ScheduledExecutorService workExecutor) {
        this(db, remote, continuous, null, workExecutor);
    }

    /**
     * Private Constructor
     * @exclude
     */
    @InterfaceAudience.Private
    /* package */ Replication(Database db, URL remote, boolean continuous, HttpClientFactory clientFactory, ScheduledExecutorService workExecutor) {

        this.db = db;
        this.continuous = continuous;
        this.workExecutor = workExecutor;
        this.remote = remote;
        this.remoteRequestExecutor = Executors.newFixedThreadPool(EXECUTOR_THREAD_POOL_SIZE);
        this.changeListeners = Collections.synchronizedList(new ArrayList<ChangeListener>());
        this.online = true;
        this.requestHeaders = new HashMap<String, Object>();
        this.requests = Collections.synchronizedMap(new HashMap<RemoteRequest, Future>());

        if (remote.getQuery() != null && !remote.getQuery().isEmpty()) {

            URI uri = URI.create(remote.toExternalForm());

            String personaAssertion = URIUtils.getQueryParameter(uri, PersonaAuthorizer.QUERY_PARAMETER);
            if (personaAssertion != null && !personaAssertion.isEmpty()) {
                String email = PersonaAuthorizer.registerAssertion(personaAssertion);
                PersonaAuthorizer authorizer = new PersonaAuthorizer(email);
                setAuthorizer(authorizer);
            }

            String facebookAccessToken = URIUtils.getQueryParameter(uri, FacebookAuthorizer.QUERY_PARAMETER);
            if (facebookAccessToken != null && !facebookAccessToken.isEmpty()) {
                String email = URIUtils.getQueryParameter(uri, FacebookAuthorizer.QUERY_PARAMETER_EMAIL);
                FacebookAuthorizer authorizer = new FacebookAuthorizer(email);
                URL remoteWithQueryRemoved = null;
                try {
                    remoteWithQueryRemoved = new URL(remote.getProtocol(), remote.getHost(), remote.getPort(), remote.getPath());
                } catch (MalformedURLException e) {
                    throw new IllegalArgumentException(e);
                }
                authorizer.registerAccessToken(facebookAccessToken, email, remoteWithQueryRemoved.toExternalForm());
                setAuthorizer(authorizer);
            }

            // we need to remove the query from the URL, since it will cause problems when
            // communicating with sync gw / couchdb
            try {
                this.remote = new URL(remote.getProtocol(), remote.getHost(), remote.getPort(), remote.getPath());
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(e);
            }

        }

        batcher = new Batcher<RevisionInternal>(workExecutor, INBOX_CAPACITY, PROCESSOR_DELAY, new BatchProcessor<RevisionInternal>() {
            @Override
            public void process(List<RevisionInternal> inbox) {
                Log.v(Database.TAG, "*** " + toString() + ": BEGIN processInbox (" + inbox.size() + " sequences)");
                processInbox(new RevisionList(inbox));
                Log.v(Database.TAG, "*** " + toString() + ": END processInbox (lastSequence=" + lastSequence + ")");
                updateActive();
            }
        });

        setClientFactory(clientFactory);
        // this.clientFactory = clientFactory != null ? clientFactory : CouchbaseLiteHttpClientFactory.INSTANCE;

    }

    /**
     * Set the HTTP client factory if one was passed in, or use the default
     * set in the manager if available.
     * @param clientFactory
     */
    @InterfaceAudience.Private
    protected void setClientFactory(HttpClientFactory clientFactory) {
        Manager manager = null;
        if (this.db != null) {
            manager = this.db.getManager();
        }
        HttpClientFactory managerClientFactory = null;
        if (manager != null) {
            managerClientFactory = manager.getDefaultHttpClientFactory();
        }
        if (clientFactory != null) {
            this.clientFactory = clientFactory;
        } else {
            if (managerClientFactory != null) {
                this.clientFactory = managerClientFactory;
            } else {
                this.clientFactory = CouchbaseLiteHttpClientFactory.INSTANCE;
            }
        }
    }

    /**
     * Get the local database which is the source or target of this replication
     */
    @InterfaceAudience.Public
    public Database getLocalDatabase() {
        return db;
    }

    /**
     * Get the remote URL which is the source or target of this replication
     */
    @InterfaceAudience.Public
    public URL getRemoteUrl() {
        return remote;
    }

    /**
     * Is this a pull replication?  (Eg, it pulls data from Sync Gateway -> Device running CBL?)
     */
    @InterfaceAudience.Public
    public abstract boolean isPull();


    /**
     * Should the target database be created if it doesn't already exist? (Defaults to NO).
     */
    @InterfaceAudience.Public
    public abstract boolean shouldCreateTarget();

    /**
     * Set whether the target database be created if it doesn't already exist?
     */
    @InterfaceAudience.Public
    public abstract void setCreateTarget(boolean createTarget);

    /**
     * Should the replication operate continuously, copying changes as soon as the
     * source database is modified? (Defaults to NO).
     */
    @InterfaceAudience.Public
    public boolean isContinuous() {
        return continuous;
    }

    /**
     * Set whether the replication should operate continuously.
     */
    @InterfaceAudience.Public
    public void setContinuous(boolean continuous) {
        if (!isRunning()) {
            this.continuous = continuous;
        }
    }

    /**
     * Name of an optional filter function to run on the source server. Only documents for
     * which the function returns true are replicated.
     *
     * For a pull replication, the name looks like "designdocname/filtername".
     * For a push replication, use the name under which you registered the filter with the Database.
     */
    @InterfaceAudience.Public
    public String getFilter() {
        return filterName;
    }

    /**
     * Set the filter to be used by this replication
     */
    @InterfaceAudience.Public
    public void setFilter(String filterName) {
        this.filterName = filterName;
    }

    /**
     * Parameters to pass to the filter function.  Should map strings to strings.
     */
    @InterfaceAudience.Public
    public Map<String, Object> getFilterParams() {
        return filterParams;
    }

    /**
     * Set parameters to pass to the filter function.
     */
    @InterfaceAudience.Public
    public void setFilterParams(Map<String, Object> filterParams) {
        this.filterParams = filterParams;
    }

    /**
     * List of Sync Gateway channel names to filter by; a nil value means no filtering, i.e. all
     * available channels will be synced.  Only valid for pull replications whose source database
     * is on a Couchbase Sync Gateway server.  (This is a convenience that just reads or
     * changes the values of .filter and .query_params.)
     */
    @InterfaceAudience.Public
    public List<String> getChannels() {
        if (filterParams == null || filterParams.isEmpty()) {
            return new ArrayList<String>();
        }
        String params = (String) filterParams.get(CHANNELS_QUERY_PARAM);
        if (!isPull() || getFilter() == null || !getFilter().equals(BY_CHANNEL_FILTER_NAME) || params == null || params.isEmpty()) {
            return new ArrayList<String>();
        }
        String[] paramsArray = params.split(",");
        return new ArrayList<String>(Arrays.asList(paramsArray));
    }

    /**
     * Set the list of Sync Gateway channel names
     */
    @InterfaceAudience.Public
    public void setChannels(List<String> channels) {
        if (channels != null && !channels.isEmpty()) {
            if (!isPull()) {
                Log.w(Database.TAG, "filterChannels can only be set in pull replications");
                return;
            }
            setFilter(BY_CHANNEL_FILTER_NAME);
            Map<String, Object> filterParams = new HashMap<String, Object>();
            filterParams.put(CHANNELS_QUERY_PARAM, TextUtils.join(",", channels));
            setFilterParams(filterParams);
        } else if (getFilter().equals(BY_CHANNEL_FILTER_NAME)) {
            setFilter(null);
            setFilterParams(null);
        }
    }

    /**
     * Extra HTTP headers to send in all requests to the remote server.
     * Should map strings (header names) to strings.
     */
    @InterfaceAudience.Public
    public Map<String, Object> getHeaders() {
        return requestHeaders;
    }

    /**
     * Set Extra HTTP headers to be sent in all requests to the remote server.
     */
    @InterfaceAudience.Public
    public void setHeaders(Map<String, Object> requestHeadersParam) {
        if (requestHeadersParam != null && !requestHeaders.equals(requestHeadersParam)) {
            requestHeaders = requestHeadersParam;
        }
    }

    /**
     * Gets the documents to specify as part of the replication.
     */
    @InterfaceAudience.Public
    public List<String> getDocIds() {
        return documentIDs;
    }

    /**
     * Sets the documents to specify as part of the replication.
     */
    @InterfaceAudience.Public
    public void setDocIds(List<String> docIds) {
        documentIDs = docIds;
    }

    /**
     * The replication's current state, one of {stopped, offline, idle, active}.
     */
    @InterfaceAudience.Public
    public ReplicationStatus getStatus() {
        return status;
    }

    /**
     * The number of completed changes processed, if the task is active, else 0 (observable).
     */
    @InterfaceAudience.Public
    public int getCompletedChangesCount() {
        return completedChangesCount;
    }

    /**
     * The total number of changes to be processed, if the task is active, else 0 (observable).
     */
    @InterfaceAudience.Public
    public int getChangesCount() {
        return changesCount;
    }

    /**
     * True while the replication is running, False if it's stopped.
     * Note that a continuous replication never actually stops; it only goes idle waiting for new
     * data to appear.
     */
    @InterfaceAudience.Public
    public boolean isRunning() {
        return running;
    }

    /**
     * The error status of the replication, or null if there have not been any errors since
     * it started.
     */
    @InterfaceAudience.Public
    public Throwable getLastError() {
        return error;
    }

    /**
     * Starts the replication, asynchronously.
     */
    @InterfaceAudience.Public
    public void start() {

        if (!db.isOpen()) { // Race condition: db closed before replication starts
            Log.w(Database.TAG, "Not starting replication because db.isOpen() returned false.");
            return;
        }

        if (running) {
            return;
        }

        db.addReplication(this);
        db.addActiveReplication(this);


        this.sessionID = String.format("repl%03d", ++lastSessionID);
        Log.v(Database.TAG, toString() + " STARTING ...");
        running = true;
        lastSequence = null;

        checkSession();

        db.getManager().getContext().getNetworkReachabilityManager().addNetworkReachabilityListener(this);

    }

    /**
     * Stops replication, asynchronously.
     */
    @InterfaceAudience.Public
    public void stop() {
        if (!running) {
            return;
        }
        Log.v(Database.TAG, this + ": STOPPING...");
        batcher.clear();  // no sense processing any pending changes
        continuous = false;
        stopRemoteRequests();
        cancelPendingRetryIfReady();
        db.forgetReplication(this);
        if (running && asyncTaskCount <= 0) {
            Log.v(Database.TAG, this + ": calling stopped()");
            stopped();
        } else {
            Log.v(Database.TAG, this + ": not calling stopped().  running: " + running + " asyncTaskCount: " + asyncTaskCount);
        }
    }

    /**
     * Restarts a completed or failed replication.
     */
    @InterfaceAudience.Public
    public void restart() {
        // TODO: add the "started" flag and check it here
        stop();
        start();
    }

    /**
     * Adds a change delegate that will be called whenever the Replication changes.
     */
    @InterfaceAudience.Public
    public void addChangeListener(ChangeListener changeListener) {
        changeListeners.add(changeListener);
    }

    /**
     * Return a string representation of this replication.
     *
     * The credentials will be masked in order to avoid passwords leaking into logs.
     */
    @Override
    @InterfaceAudience.Public
    public String toString() {
        String maskedRemoteWithoutCredentials = (remote != null ? remote.toExternalForm() : "");
        maskedRemoteWithoutCredentials = maskedRemoteWithoutCredentials.replaceAll("://.*:.*@", "://---:---@");
        String name = getClass().getSimpleName() + "@" + Integer.toHexString(hashCode()) + "[" + maskedRemoteWithoutCredentials + "]";
        return name;
    }

    /**
     * The type of event raised by a Replication when any of the following
     * properties change: mode, running, error, completed, total.
     */
    @InterfaceAudience.Public
    public static class ChangeEvent {

        private Replication source;

        public ChangeEvent(Replication source) {
            this.source = source;
        }

        public Replication getSource() {
            return source;
        }

    }

    /**
     * A delegate that can be used to listen for Replication changes.
     */
    @InterfaceAudience.Public
    public static interface ChangeListener {
        public void changed(ChangeEvent event);
    }

    /**
     * Removes the specified delegate as a listener for the Replication change event.
     */
    @InterfaceAudience.Public
    public void removeChangeListener(ChangeListener changeListener) {
        changeListeners.remove(changeListener);
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void setAuthorizer(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public Authorizer getAuthorizer() {
        return authorizer;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void databaseClosing() {
        saveLastSequence();
        stop();
        clearDbRef();
    }

    /**
     * If we're in the middle of saving the checkpoint and waiting for a response, by the time the
     * response arrives _db will be nil, so there won't be any way to save the checkpoint locally.
     * To avoid that, pre-emptively save the local checkpoint now.
     *
     * @exclude
     */
    private void clearDbRef() {
        if (savingCheckpoint && lastSequence != null) {
            db.setLastSequence(lastSequence, remoteCheckpointDocID(), !isPull());
            db = null;
        }
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public String getLastSequence() {
        return lastSequence;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void setLastSequence(String lastSequenceIn) {
        if (lastSequenceIn != null && !lastSequenceIn.equals(lastSequence)) {
            Log.v(Database.TAG, toString() + ": Setting lastSequence to " + lastSequenceIn + " from( " + lastSequence + ")");
            lastSequence = lastSequenceIn;
            if (!lastSequenceChanged) {
                lastSequenceChanged = true;
                workExecutor.schedule(new Runnable() {

                    @Override
                    public void run() {
                        saveLastSequence();
                    }
                }, 2 * 1000, TimeUnit.MILLISECONDS);
            }
        }
    }


    @InterfaceAudience.Private
    /* package */ void setCompletedChangesCount(int processed) {
        assert(processed > 0);
        Log.d(Database.TAG, "Updating changesCountCompleted count from " + this.completedChangesCount + " -> " + processed);
        this.completedChangesCount = processed;
        notifyChangeListeners();

    }

    @InterfaceAudience.Private
    /* package */ void setChangesCount(int total) {
        assert(total > 0);
        Log.d(Database.TAG, "Updating changesCount count from " + this.changesCount + " -> " + total);
        this.changesCount = total;
        notifyChangeListeners();
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public String getSessionID() {
        return sessionID;
    }

    @InterfaceAudience.Private
    protected void checkSession() {
        if (getAuthorizer() != null && getAuthorizer().usesCookieBasedLogin()) {
            checkSessionAtPath("/_session");
        } else {
            fetchRemoteCheckpointDoc();
        }
    }

    @InterfaceAudience.Private
    protected void checkSessionAtPath(final String sessionPath) {

        Log.d(Database.TAG, this + "|" + Thread.currentThread() + ": checkSessionAtPath() calling asyncTaskStarted()");

        asyncTaskStarted();
        sendAsyncRequest("GET", sessionPath, null, new RemoteRequestCompletionBlock() {

            @Override
            public void onCompletion(Object result, Throwable error) {

                try {
                    if (error != null) {
                        // If not at /db/_session, try CouchDB location /_session
                        if (error instanceof HttpResponseException &&
                                ((HttpResponseException) error).getStatusCode() == 404 &&
                                sessionPath.equalsIgnoreCase("/_session")) {

                            checkSessionAtPath("_session");
                            return;
                        }
                        Log.e(Database.TAG, this + ": Session check failed", error);
                        setError(error);

                    } else {
                        Map<String, Object> response = (Map<String, Object>) result;
                        Map<String, Object> userCtx = (Map<String, Object>) response.get("userCtx");
                        String username = (String) userCtx.get("name");
                        if (username != null && username.length() > 0) {
                            Log.d(Database.TAG, String.format("%s Active session, logged in as %s", this, username));
                            fetchRemoteCheckpointDoc();
                        } else {
                            Log.d(Database.TAG, String.format("%s No active session, going to login", this));
                            login();
                        }
                    }

                } finally {
                    Log.d(Database.TAG, this + "|" + Thread.currentThread() + ": checkSessionAtPath() calling asyncTaskFinished()");
                    asyncTaskFinished(1);
                }
            }

        });
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public abstract void beginReplicating();

    @InterfaceAudience.Private
    protected void stopped() {
        Log.v(Database.TAG, this + ": STOPPED");
        running = false;

        notifyChangeListeners();

        saveLastSequence();

        Log.v(Database.TAG, this + " set batcher to null");

        batcher = null;

        if (db != null) {
            db.getManager().getContext().getNetworkReachabilityManager().removeNetworkReachabilityListener(this);
        }

        clearDbRef();  // db no longer tracks me so it won't notify me when it closes; clear ref now

    }

    @InterfaceAudience.Private
    private void notifyChangeListeners() {
        updateProgress();
        synchronized (changeListeners) {
            for (ChangeListener listener : changeListeners) {
                ChangeEvent changeEvent = new ChangeEvent(this);
                listener.changed(changeEvent);
            }
        }
    }

    @InterfaceAudience.Private
    protected void login() {
        Map<String, String> loginParameters = getAuthorizer().loginParametersForSite(remote);
        if (loginParameters == null) {
            Log.d(Database.TAG, String.format("%s: %s has no login parameters, so skipping login", this, getAuthorizer()));
            fetchRemoteCheckpointDoc();
            return;
        }

        final String loginPath = getAuthorizer().loginPathForSite(remote);

        Log.d(Database.TAG, String.format("%s: Doing login with %s at %s", this, getAuthorizer().getClass(), loginPath));

        Log.d(Database.TAG, this + "|" + Thread.currentThread() + ": login() calling asyncTaskStarted()");

        asyncTaskStarted();
        sendAsyncRequest("POST", loginPath, loginParameters, new RemoteRequestCompletionBlock() {

            @Override
            public void onCompletion(Object result, Throwable e) {
                try {
                    if (e != null) {
                        Log.d(Database.TAG, String.format("%s: Login failed for path: %s", this, loginPath));
                        setError(e);
                    }
                    else {
                        Log.d(Database.TAG, String.format("%s: Successfully logged in!", this));
                        fetchRemoteCheckpointDoc();
                    }
                } finally {
                    Log.d(Database.TAG, this + "|" + Thread.currentThread() + ": login() calling asyncTaskFinished()");
                    asyncTaskFinished(1);
                }
            }

        });

    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public synchronized void asyncTaskStarted() {
        Log.d(Database.TAG, this + "|" + Thread.currentThread().toString() + ": asyncTaskStarted() called, asyncTaskCount: " + asyncTaskCount);
        if (asyncTaskCount++ == 0) {
            updateActive();
        }
        Log.d(Database.TAG, "asyncTaskStarted() updated asyncTaskCount to " + asyncTaskCount);
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public synchronized void asyncTaskFinished(int numTasks) {
        Log.d(Database.TAG, this + "|" + Thread.currentThread().toString() + ": asyncTaskFinished() called, asyncTaskCount: " + asyncTaskCount + " numTasks: " + numTasks);
        this.asyncTaskCount -= numTasks;
        Log.d(Database.TAG, "asyncTaskFinished() updated asyncTaskCount to: " + asyncTaskCount);
        assert(asyncTaskCount >= 0);
        if (asyncTaskCount == 0) {
            updateActive();
        }
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void updateActive() {
        try {
            int batcherCount = 0;
            if (batcher != null) {
                batcherCount = batcher.count();
            } else {
                Log.w(Database.TAG, this + ": batcher object is null.  dumpStack()");
                Thread.dumpStack();
            }
            boolean newActive = batcherCount > 0 || asyncTaskCount > 0;
            if (active != newActive) {
                Log.d(Database.TAG, this + " Progress: set active = " + newActive + " asyncTaskCount: " + asyncTaskCount + " batcherCount: " + batcherCount );
                active = newActive;
                notifyChangeListeners();

                if (!active) {
                    if (!continuous) {
                        Log.d(Database.TAG, this + " since !continuous, calling stopped()");
                        stopped();
                    } else if (error != null) /*(revisionsFailed > 0)*/ {
                        String msg = String.format(
                                "%s: Failed to xfer %d revisions, will retry in %d sec",
                                this,
                                revisionsFailed,
                                RETRY_DELAY);
                        Log.d(Database.TAG, msg);
                        cancelPendingRetryIfReady();
                        scheduleRetryIfReady();
                    }

                }

            }
        } catch (Exception e) {
            Log.e(Database.TAG, "Exception in updateActive()", e);
        }
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void addToInbox(RevisionInternal rev) {
        batcher.queueObject(rev);
        updateActive();
    }

    @InterfaceAudience.Private
    protected void processInbox(RevisionList inbox) {

    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void sendAsyncRequest(String method, String relativePath, Object body, RemoteRequestCompletionBlock onCompletion) {
        try {
            String urlStr = buildRelativeURLString(relativePath);
            URL url = new URL(urlStr);
            sendAsyncRequest(method, url, body, onCompletion);
        } catch (MalformedURLException e) {
            Log.e(Database.TAG, "Malformed URL for async request", e);
        }
    }

    @InterfaceAudience.Private
    /* package */ String buildRelativeURLString(String relativePath) {

        // the following code is a band-aid for a system problem in the codebase
        // where it is appending "relative paths" that start with a slash, eg:
        //     http://dotcom/db/ + /relpart == http://dotcom/db/relpart
        // which is not compatible with the way the java url concatonation works.

        String remoteUrlString = remote.toExternalForm();
        if (remoteUrlString.endsWith("/") && relativePath.startsWith("/")) {
            remoteUrlString = remoteUrlString.substring(0, remoteUrlString.length() - 1);
        }
        return remoteUrlString + relativePath;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void sendAsyncRequest(String method, URL url, Object body, final RemoteRequestCompletionBlock onCompletion) {

        final RemoteRequest request = new RemoteRequest(workExecutor, clientFactory, method, url, body, getHeaders(), onCompletion);

        request.setOnPreCompletion(new RemoteRequestCompletionBlock() {
            @Override
            public void onCompletion(Object result, Throwable e) {
                if (serverType == null && result instanceof HttpResponse) {
                    HttpResponse response = (HttpResponse) result;
                    Header serverHeader = response.getFirstHeader("Server");
                    if (serverHeader != null) {
                        String serverVersion = serverHeader.getValue();
                        Log.d(Database.TAG, "serverVersion: " + serverVersion);
                        serverType = serverVersion;
                    }
                }
            }
        });

        request.setOnPostCompletion(new RemoteRequestCompletionBlock() {
            @Override
            public void onCompletion(Object result, Throwable e) {
                requests.remove(request);
            }
        });


        if (remoteRequestExecutor.isTerminated()) {
            String msg = "sendAsyncRequest called, but remoteRequestExecutor has been terminated";
            throw new IllegalStateException(msg);
        }
        Future future = remoteRequestExecutor.submit(request);
        requests.put(request, future);

    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void sendAsyncMultipartDownloaderRequest(String method, String relativePath, Object body, Database db, RemoteRequestCompletionBlock onCompletion) {
        try {

            String urlStr = buildRelativeURLString(relativePath);
            URL url = new URL(urlStr);

            RemoteMultipartDownloaderRequest request = new RemoteMultipartDownloaderRequest(
                    workExecutor,
                    clientFactory,
                    method,
                    url,
                    body,
                    db,
                    getHeaders(),
                    onCompletion);
            remoteRequestExecutor.execute(request);
        } catch (MalformedURLException e) {
            Log.e(Database.TAG, "Malformed URL for async request", e);
        }
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void sendAsyncMultipartRequest(String method, String relativePath, MultipartEntity multiPartEntity, RemoteRequestCompletionBlock onCompletion) {
        URL url = null;
        try {
            String urlStr = buildRelativeURLString(relativePath);
            url = new URL(urlStr);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
        RemoteMultipartRequest request = new RemoteMultipartRequest(
                workExecutor,
                clientFactory,
                method,
                url,
                multiPartEntity,
                getHeaders(),
                onCompletion);
        remoteRequestExecutor.execute(request);
    }

    // https://github.com/couchbase/couchbase-lite-java-core/issues/40
    public void addPrincipal(Principal principal) {
        if (principal == null) {
            throw new IllegalArgumentException();
        }

        principals.putIfAbsent(principal, true);
    }

    // https://github.com/couchbase/couchbase-lite-java-core/issues/40
    public boolean removePrincipal(Principal principal) {
        if (principal == null) {
            throw new IllegalArgumentException();
        }

        return principals.remove(principal);
    }

    // https://github.com/couchbase/couchbase-lite-java-core/issues/40
    public Enumeration<Principal> getPrincipals() {
        return principals.keys();
    }

    /**
     * CHECKPOINT STORAGE: *
     */

    @InterfaceAudience.Private
    /* package */ void maybeCreateRemoteDB() {
        // Pusher overrides this to implement the .createTarget option
    }

    /**
     * This is the _local document ID stored on the remote server to keep track of state.
     * Its ID is based on the local database ID (the private one, to make the result unguessable)
     * and the remote database's URL.
     *
     * @exclude
     */
    @InterfaceAudience.Private
    public String remoteCheckpointDocID() {

        /*  TODO: enhance to match iOS version
            NSMutableDictionary* spec = $mdict({@"localUUID", _db.privateUUID},
                                       {@"remoteURL", _remote.absoluteString},
                                       {@"push", @(self.isPush)},
                                       {@"continuous", (self.continuous ? nil : $false)},
                                       {@"filter", _filterName},
                                       {@"filterParams", _filterParameters},
                                     //{@"headers", _requestHeaders}, (removed; see #143)
                                       {@"docids", _docIDs});
         */

        if (db == null) {
            return null;
        }
        String input = db.privateUUID() + "\n" + remote.toExternalForm() + "\n" + (!isPull() ? "1" : "0");
        return Misc.TDHexSHA1Digest(input.getBytes());
    }

    @InterfaceAudience.Private
    private boolean is404(Throwable e) {
        if (e instanceof HttpResponseException) {
            return ((HttpResponseException) e).getStatusCode() == 404;
        }
        return false;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void fetchRemoteCheckpointDoc() {
        lastSequenceChanged = false;
        String checkpointId = remoteCheckpointDocID();
        final String localLastSequence = db.lastSequenceWithCheckpointId(checkpointId);

        Log.d(Database.TAG, this + "|" + Thread.currentThread() + ": fetchRemoteCheckpointDoc() calling asyncTaskStarted()");

        asyncTaskStarted();
        sendAsyncRequest("GET", "/_local/" + checkpointId, null, new RemoteRequestCompletionBlock() {

            @Override
            public void onCompletion(Object result, Throwable e) {
                try {

                    if (e != null && !is404(e)) {
                        Log.d(Database.TAG, this + " error getting remote checkpoint: " + e);
                        setError(e);
                    } else {
                        if (e != null && is404(e)) {
                            Log.d(Database.TAG, this + " 404 error getting remote checkpoint " + remoteCheckpointDocID() + ", calling maybeCreateRemoteDB");
                            maybeCreateRemoteDB();
                        }
                        Map<String, Object> response = (Map<String, Object>) result;
                        remoteCheckpoint = response;
                        String remoteLastSequence = null;
                        if (response != null) {
                            remoteLastSequence = (String) response.get("lastSequence");
                        }
                        if (remoteLastSequence != null && remoteLastSequence.equals(localLastSequence)) {
                            lastSequence = localLastSequence;
                            Log.v(Database.TAG, this + ": Replicating from lastSequence=" + lastSequence);
                        } else {
                            Log.v(Database.TAG, this + ": lastSequence mismatch: I had " + localLastSequence + ", remote had " + remoteLastSequence);
                        }
                        beginReplicating();
                    }
                } finally {
                    Log.d(Database.TAG, this + "|" + Thread.currentThread() + ": fetchRemoteCheckpointDoc() calling asyncTaskFinished()");
                    asyncTaskFinished(1);
                }
            }

        });
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void saveLastSequence() {
        if (!lastSequenceChanged) {
            return;
        }
        if (savingCheckpoint) {
            // If a save is already in progress, don't do anything. (The completion block will trigger
            // another save after the first one finishes.)
            overdueForSave = true;
            return;
        }

        lastSequenceChanged = false;
        overdueForSave = false;

        Log.d(Database.TAG, this + " saveLastSequence() called. lastSequence: " + lastSequence);
        final Map<String, Object> body = new HashMap<String, Object>();
        if (remoteCheckpoint != null) {
            body.putAll(remoteCheckpoint);
        }
        body.put("lastSequence", lastSequence);

        String remoteCheckpointDocID = remoteCheckpointDocID();
        if (remoteCheckpointDocID == null) {
            Log.w(Database.TAG, this + ": remoteCheckpointDocID is null, aborting saveLastSequence()");
            return;
        }

        savingCheckpoint = true;
        final String checkpointID = remoteCheckpointDocID;
        Log.d(Database.TAG, this + " put remote _local document.  checkpointID: " + checkpointID);
        sendAsyncRequest("PUT", "/_local/" + checkpointID, body, new RemoteRequestCompletionBlock() {

            @Override
            public void onCompletion(Object result, Throwable e) {
                savingCheckpoint = false;
                if (e != null) {
                    Log.w(Database.TAG, this + ": Unable to save remote checkpoint", e);
                }
                if (db == null) {
                    Log.w(Database.TAG, this + ": Database is null, ignoring remote checkpoint response");
                    return;
                }
                if (e != null) {
                    // Failed to save checkpoint:
                    switch (getStatusFromError(e)) {
                        case Status.NOT_FOUND:
                            remoteCheckpoint = null;  // doc deleted or db reset
                            overdueForSave = true; // try saving again
                            break;
                        case Status.CONFLICT:
                            refreshRemoteCheckpointDoc();
                            break;
                        default:
                            // TODO: On 401 or 403, and this is a pull, remember that remote
                            // TODo: is read-only & don't attempt to read its checkpoint next time.
                            break;
                    }
                } else {
                    // Saved checkpoint:
                    Map<String, Object> response = (Map<String, Object>) result;
                    body.put("_rev", response.get("rev"));
                    remoteCheckpoint = body;
                    db.setLastSequence(lastSequence, checkpointID, !isPull());
                }
                if (overdueForSave) {
                    saveLastSequence();
                }

            }
        });
    }

    @InterfaceAudience.Public
    public boolean goOffline() {
        if (!online) {
            return false;
        }
        if (db == null) {
            return false;
        }
        db.runAsync(new AsyncTask() {
            @Override
            public void run(Database database) {
                Log.d(Database.TAG, this + ": Going offline");
                online = false;
                stopRemoteRequests();
                updateProgress();
                notifyChangeListeners();
            }
        });
        return true;
    }

    @InterfaceAudience.Public
    public boolean goOnline() {
        if (online) {
            return false;
        }
        if (db == null) {
            return false;
        }
        db.runAsync(new AsyncTask() {
            @Override
            public void run(Database database) {
                Log.d(Database.TAG, this + ": Going online");
                online = true;

                if (running) {
                    lastSequence = null;
                    setError(null);
                }
                remoteRequestExecutor = Executors.newCachedThreadPool();
                checkSession();
                notifyChangeListeners();
            }
        });

        return true;
    }

    @InterfaceAudience.Private
    private void stopRemoteRequests() {
        Log.d(Database.TAG, this + ": stopRemoteRequests() cancelling " + requests.size() + " requests");
        synchronized (requests) {
            for (RemoteRequest request : requests.keySet()) {
                Future future = requests.get(request);
                Log.d(Database.TAG, this + ": cancelling future " + future + " for request: " + request + " isCancelled: " + future.isCancelled() + " isDone: " + future.isDone());
                boolean result = future.cancel(true);
                Log.d(Database.TAG, this + ": cancelled future, result: " + result);
            }
        }
    }

    @InterfaceAudience.Private
    /* package */ void updateProgress() {
        if (!isRunning()) {
            status = ReplicationStatus.REPLICATION_STOPPED;
        } else if (!online) {
            status = ReplicationStatus.REPLICATION_OFFLINE;
        } else {
            if (active) {
                status = ReplicationStatus.REPLICATION_ACTIVE;
            } else {
                status = ReplicationStatus.REPLICATION_IDLE;
            }
        }
    }

    @InterfaceAudience.Private
    protected void setError(Throwable throwable) {
        // TODO
        /*
        if (error.code == NSURLErrorCancelled && $equal(error.domain, NSURLErrorDomain))
            return;
         */

        if (throwable != error) {
            Log.e(Database.TAG, this + " Progress: set error = " + throwable);
            error = throwable;
            notifyChangeListeners();
        }

    }

    @InterfaceAudience.Private
    protected void revisionFailed() {
        // Remember that some revisions failed to transfer, so we can later retry.
        ++revisionsFailed;
    }

    /**
     * Called after a continuous replication has gone idle, but it failed to transfer some revisions
     * and so wants to try again in a minute. Should be overridden by subclasses.
     */
    @InterfaceAudience.Private
    protected void retry() {
        setError(null);
    }

    @InterfaceAudience.Private
    protected void retryIfReady() {
        if (!running) {
            return;
        }
        if (online) {
            Log.d(Database.TAG, this + " RETRYING, to transfer missed revisions...");
            revisionsFailed = 0;
            cancelPendingRetryIfReady();
            retry();
        } else {
            scheduleRetryIfReady();
        }
    }

    @InterfaceAudience.Private
    protected void cancelPendingRetryIfReady() {
        if (retryIfReadyFuture != null && retryIfReadyFuture.isCancelled() == false) {
            retryIfReadyFuture.cancel(true);
        }
    }

    @InterfaceAudience.Private
    protected void scheduleRetryIfReady() {
        retryIfReadyFuture = workExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                retryIfReady();
            }
        }, RETRY_DELAY, TimeUnit.SECONDS);
    }

    @InterfaceAudience.Private
    private int getStatusFromError(Throwable t) {
        if (t instanceof CouchbaseLiteException) {
            CouchbaseLiteException couchbaseLiteException = (CouchbaseLiteException) t;
            return couchbaseLiteException.getCBLStatus().getCode();
        }
        return Status.UNKNOWN;
    }

    /**
     * Variant of -fetchRemoveCheckpointDoc that's used while replication is running, to reload the
     * checkpoint to get its current revision number, if there was an error saving it.
     */
    @InterfaceAudience.Private
    private void refreshRemoteCheckpointDoc() {
        Log.d(Database.TAG, this + ": Refreshing remote checkpoint to get its _rev...");
        savingCheckpoint = true;
        Log.d(Database.TAG, this + "|" + Thread.currentThread() + ": refreshRemoteCheckpointDoc() calling asyncTaskStarted()");
        asyncTaskStarted();
        sendAsyncRequest("GET", "/_local/" + remoteCheckpointDocID(), null, new RemoteRequestCompletionBlock() {

            @Override
            public void onCompletion(Object result, Throwable e) {
                try {
                    if (db == null) {
                        Log.w(Database.TAG, this + ": db == null while refreshing remote checkpoint.  aborting");
                        return;
                    }
                    savingCheckpoint = false;
                    if (e != null && getStatusFromError(e) != Status.NOT_FOUND) {
                        Log.e(Database.TAG, this + ": Error refreshing remote checkpoint", e);
                    } else {
                        Log.d(Database.TAG, this + ": Refreshed remote checkpoint: " + result);
                        remoteCheckpoint = (Map<String, Object>) result;
                        lastSequenceChanged = true;
                        saveLastSequence();  // try saving again
                    }
                } finally {
                    Log.d(Database.TAG, this + "|" + Thread.currentThread() + ": refreshRemoteCheckpointDoc() calling asyncTaskFinished()");
                    asyncTaskFinished(1);
                }
            }
        });

    }

    @Override
    @InterfaceAudience.Private
    public void networkReachable() {
        goOnline();
    }

    @Override
    @InterfaceAudience.Private
    public void networkUnreachable() {
        goOffline();
    }

    @InterfaceAudience.Private
    /* package */ boolean serverIsSyncGatewayVersion(String minVersion) {
        String prefix = "Couchbase Sync Gateway/";
        if (serverType == null) {
            return false;
        } else {
            if (serverType.startsWith(prefix)) {
                String versionString = serverType.substring(prefix.length());
                return versionString.compareTo(minVersion) >= 0;
            }

        }
        return false;
    }

    @InterfaceAudience.Private
    /* package */ void setServerType(String serverType) {
        this.serverType = serverType;
    }
}
