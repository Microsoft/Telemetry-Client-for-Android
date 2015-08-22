package com.microsoft.cll.android;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Queues events for upload
 */
public class EventHandler extends ScheduledWorker
{
    private final String TAG = "EventHandler";
    final AbstractHandler criticalHandler;
    final AbstractHandler normalHandler;
    private final ClientTelemetry clientTelemetry;
    private final List<ICllEvents> cllEvents;
    private final ILogger logger;
    private EventSender sender;

    private URL endpoint;
    private int sampleId;

    protected EventHandler(ClientTelemetry clientTelemetry, List<ICllEvents> cllEvents, ILogger logger, AbstractHandler normalEventHandler, AbstractHandler criticalEventAbstractHandler)
    {
        super(SettingsStore.getCllSettingsAsInt(SettingsStore.Settings.QUEUEDRAININTERVAL));

        this.clientTelemetry    = clientTelemetry;
        this.cllEvents          = cllEvents;
        this.logger             = logger;
        this.normalHandler      = normalEventHandler;
        this.criticalHandler    = criticalEventAbstractHandler;
        this.sampleId           = -1;
    }

    /**
     * An event queue for managing events and sending them
     */
    public EventHandler(ClientTelemetry clientTelemetry, List<ICllEvents> cllEvents, ILogger logger, String filePath)
    {
        super(SettingsStore.getCllSettingsAsInt(SettingsStore.Settings.QUEUEDRAININTERVAL));

        this.clientTelemetry    = clientTelemetry;
        this.cllEvents          = cllEvents;
        this.logger             = logger;
        this.criticalHandler    = new CriticalEventHandler(logger, filePath);
        this.normalHandler      = new NormalEventHandler(logger, filePath);
        this.sampleId           = -1;
    }

    /**
     * Timed queue drain
     */
    @Override
    public void run()
    {
        // Check to see if the interval at which we should drain has changed
        if(interval != SettingsStore.getCllSettingsAsInt(SettingsStore.Settings.QUEUEDRAININTERVAL)) {
            nextExecution.cancel(false);
            interval = SettingsStore.getCllSettingsAsInt(SettingsStore.Settings.QUEUEDRAININTERVAL);
            nextExecution = executor.scheduleAtFixedRate(this, interval, interval, TimeUnit.SECONDS);
        }

        // If the next (normal periodic) scheduled drain is starting and we have a back-off retry scheduled future set, return and let the future handle sending
        if(EventQueueWriter.future != null) {
            logger.info(TAG, "Retry logic in progress, skipping normal send");
            return;
        }

        send();
    }

    @Override
    public void stop()
    {
        super.stop();
        normalHandler.close();
        criticalHandler.close();
    }

    /**
     * log an item to its appropriate storage or attempt to send the event immediately if it is real time
     * @param event The event to store and send
     * @return True if we added the event to the queue or False if we couldn't add the event
     */
    protected boolean log(SerializedEvent event)
    {
        if (Filter(event)) {
            return false;
        }

        // If real time don't queue just send, unless the send fails, then queue
        if(event.getLatency() == Cll.EventLatency.REALTIME && !isPaused) {
            boolean result = startEventQueueWriter(new EventQueueWriter(endpoint, event, clientTelemetry, cllEvents, logger, executor, this));
            if(result == true) {
                return true;
            }
        }

        return addToStorage(event);
    }

    /**
     * Adds the event to the appropriate storage
     * @param event The event to store
     * @return Whether we successfully added the event to storage
     */
    protected boolean addToStorage(SerializedEvent event)
    {
        switch (event.getPersistence()) {
            case NORMAL:
                try {
                    normalHandler.add(event.getSerializedData());
                } catch (IOException e) {
                    logger.error(TAG, "Could not add event to normal storage");
                    return false;
                } catch (FileStorage.FileFullException e) {
                    logger.warn(TAG, "No space on disk to store events");
                    return false;
                }

                break;
            case CRITICAL:
                try {
                    criticalHandler.add(event.getSerializedData());
                } catch (IOException e) {
                    logger.error(TAG, "Could not add event to normal storage");
                    return false;
                } catch (FileStorage.FileFullException e) {
                    logger.warn(TAG, "No space on disk to store events");
                    return false;
                }

                break;
            default:
                logger.error(TAG, "Unknown persistence");
                assert(false);
                break;
        }

        return true;
    }

    //region SAMPLING
    /** Filters events for whether upload
     *
     * @param event the event to sample
     * @return true if we will filter, false if we will send
     */
    private boolean Filter(SerializedEvent event)
    {
        if(!IsUploadEnabled() || !IsInSample(event)) {
            logger.info(TAG, "Filtered event");
            return true;
        }

        return false;
    }

    /**
     * If sampling is not at 100 we may drop this event
     * @param event the event to sample
     * @return true if we should be dropped, false if the event should be logged
     */
    private boolean IsInSample(SerializedEvent event) {
        if(event.getDeviceId() == null) {
            return false;
        }

        if(sampleId < 0) {
            String lastDigits = event.getDeviceId().substring(event.getDeviceId().length() - 7);
            sampleId = Integer.parseInt(lastDigits, 16) % 100;
        }

        if(sampleId < event.getSampleRate()) {
            return true;
        }

        return false;
    }

    /**
     * Checks to make sure upload status is enabled
     * @return true if upload status is enabled, false otherwise
     */
    private boolean IsUploadEnabled() {
        if(!SettingsStore.getCllSettingsAsBoolean(SettingsStore.Settings.UPLOADENABLED)) {
            return false;
        }

        return true;
    }
    //endregion

    /**
     * Drains the critical event queue and then the normal and sends the events
     * @return returns false if paused, otherwise true
     */
    protected boolean send() {
        return send(null);
    }

    /**
     * Drains events of a specific persistence and sends them
     * @param persistence The persistence of the event to drain
     * @return returns false if paused, otherwise true
     */
    protected boolean send(Cll.EventPersistence persistence)
    {
        // Don't send if we are paused
        if(isPaused) {
            return false;
        }

        List<IStorage> storages = null;

        if(persistence == null) {
            logger.info(TAG, "Draining All events");
            storages = normalHandler.getFilesForDraining();
            storages.addAll(criticalHandler.getFilesForDraining());
        } else {
            switch (persistence) {
                case NORMAL:
                    logger.info(TAG, "Draining normal events");
                    storages = normalHandler.getFilesForDraining();
                    break;
                case CRITICAL:
                    logger.info(TAG, "Draining Critical events");
                    storages = criticalHandler.getFilesForDraining();
                    break;
                default:
                    logger.error(TAG, "Unknown persistence");
                    assert(false);
                    break;
            }
        }

        if(storages != null && storages.size() != 0) {
            return startEventQueueWriter(new EventQueueWriter(endpoint, storages, clientTelemetry, cllEvents, logger, executor));
        }

        return true;
    }

    /**
     * Set the endpoint we should send events to
     * @param endpointUrl The endpoint to send to
     */
    protected void setEndpointUrl(String endpointUrl)
    {
        try {
            endpoint = new URL(endpointUrl);
        } catch (MalformedURLException e) {
            logger.error(TAG, "Bad Endpoint URL Form");
        }
    }

    /**
     * Allows us to pass in a custom sender for testing
     * @param sender The sender to use for uploading events
     */
    void setSender(EventSender sender)
    {
        this.sender = sender;
    }

    /**
     * Write all events in memory to disk
     */
    void synchronize() {
        ((NormalEventHandler)normalHandler).writeQueueToDisk();
    }

    /**
     * Starts the EventQueueWriter which sends the event
     * @param r The thread that the EventQueueWriter works on
     * @return whether we successfully started the thread
     */
    private boolean startEventQueueWriter(Runnable r)
    {
        if(endpoint == null) {
            logger.warn(TAG, "No endpoint set");
            return false;
        }

        // We needed to add this so that we can use custom senders for E2E tests to gather test results
        EventQueueWriter eqw = (EventQueueWriter) r;
        if(sender != null) {
            eqw.setSender(sender);
        }

        try {
            executor.execute(r);
        } catch (RejectedExecutionException e) {
            logger.warn(TAG, "Could not start new thread for EventQueueWriter");
            return false;
        } catch (NullPointerException e) {
            logger.error(TAG, "Executor is null. Is the cll paused or stopped?");
        }

        return true;
    }
}