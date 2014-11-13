package net.powermatcher.core.monitoring;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.powermatcher.api.TimeService;
import net.powermatcher.api.monitoring.IncomingBidEvent;
import net.powermatcher.api.monitoring.IncomingPriceEvent;
import net.powermatcher.api.monitoring.ObservableAgent;
import net.powermatcher.api.monitoring.AgentEvent;
import net.powermatcher.api.monitoring.OutgoingBidEvent;
import net.powermatcher.api.monitoring.OutgoingPriceEvent;
import net.powermatcher.core.monitoring.BaseObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import aQute.bnd.annotation.component.Activate;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Deactivate;
import aQute.bnd.annotation.component.Modified;
import aQute.bnd.annotation.component.Reference;
import aQute.bnd.annotation.metatype.Configurable;
import aQute.bnd.annotation.metatype.Meta;

/**
 * Example Observer which simply writes log entries of received events.
 */
@Component(immediate = true, designateFactory = CSVLogger.Config.class)
public class CSVLogger extends BaseObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(CSVLogger.class);

    private static final String MATCHER_QUALIFIER = "matcher";

    private static final String AGENT_QAULIFIER = "agent";

    /**
     * The header for the bidlog file
     */
    private static final String[] BID_HEADER_ROW = new String[] { "logTime", "clusterId", "id", "qualifier",
            "commodity", "currency", "minimumPrice", "maximumPrice", "minimumDemand", "maximumDemand",
            "effectiveDemand", "effectivePrice", "lastUpdateTime", "bid" };

    /**
     * The header for the pricelog file
     */
    private static final String[] PRICE_HEADER_ROW = new String[] { "logTime", "clusterId", "id", "qualifier",
            "commodity", "currency", "minimumPrice", "maximumPrice", "currentPrice", "lastUpdateTime" };

    /**
     * OSGI configuration of the {@link CSVLogger}
     */
    public static interface Config {
        @Meta.AD(
                required = false,
                description = "Filter for specific agentId's. When no filters are supplied, it will log everything.")
        List<String> filter();

        @Meta.AD(
                deflt = "agent_bid_log_'yyyyMMdd'.csv",
                description = "The pattern for the file name of the bid log file.")
        String bidlogFilenamePattern();

        @Meta.AD(
                deflt = "agent_price_log_'yyyyMMdd'.csv",
                description = "The pattern for the file name of the price log file.")
        String pricelogFilenamePattern();

        @Meta.AD(deflt = "yyyy-MM-dd HH:mm:ss", description = "The date format for the timestamps in the log.")
        String dateFormat();

        @Meta.AD(deflt = ";", description = "The field separator the logger will use.")
        String separator();

        @Meta.AD(description = "The location of the log files.")
        String logLocation();

        @Meta.AD(deflt = "30", description = "Time in seconds between file dumps.")
        long logUpdateRate();

        @Meta.AD(deflt = "csvLogger")
        String loggerId();
    }

    private List<String> filter;

    /**
     * The id of this logger instance
     */
    private String loggerId;

    /**
     * The log file {@link BidLogRecord} will be written to.
     */
    private File bidlogFile;

    /**
     * The log file {@link PriceLogRecord} will be written to
     */
    private File priceLogFile;

    /**
     * The date format for the timestamps in the log.
     */
    private DateFormat dateFormat;

    /**
     * The field separator the logger will use.
     */
    private String separator;

    /**
     * A set containing all {@link BidLogRecord} instances that haven't been written to file yet.
     */
    private Set<BidLogRecord> bidLogRecords = new HashSet<>();

    /**
     * A set containing all {@link PriceLogRecord} instances that haven't been written to file yet.
     */
    private Set<PriceLogRecord> priceLogRecords = new HashSet<>();

    /**
     * Keeps the thread alive that performs the writeLog() at a set interval
     */
    private ScheduledFuture<?> scheduledFuture;

    /**
     * Used to create a {@link ScheduledExecutorService}
     */
    private ScheduledExecutorService scheduler;

    private TimeService timeService;

    @Override
    public void update(AgentEvent event) {
        LOGGER.info("Received event: {}", event);

        if (event instanceof IncomingBidEvent) {
            BidLogRecord bidLogRecord = new BidLogRecord(event, timeService.currentDate(), dateFormat,
                    ((IncomingBidEvent) event).getBid(), MATCHER_QUALIFIER);
            bidLogRecords.add(bidLogRecord);

        } else if (event instanceof OutgoingBidEvent) {
            // TODO this differs for device agents and concentrators/auctioneers
            // addBidEvent(AGENT_QAULIFIER);

            BidLogRecord bidLogRecord = new BidLogRecord(event, timeService.currentDate(), dateFormat,
                    ((OutgoingBidEvent) event).getBid(), MATCHER_QUALIFIER);
            bidLogRecords.add(bidLogRecord);
        } else if (event instanceof IncomingPriceEvent) {
            PriceLogRecord priceLogRecord = new PriceLogRecord(event, timeService.currentDate(), dateFormat,
                    ((IncomingPriceEvent) event).getPrice(), AGENT_QAULIFIER);
            priceLogRecords.add(priceLogRecord);

        } else if (event instanceof OutgoingPriceEvent) {
            PriceLogRecord priceLogRecord = new PriceLogRecord(event, timeService.currentDate(), dateFormat,
                    ((OutgoingPriceEvent) event).getPrice(), MATCHER_QUALIFIER);
            priceLogRecords.add(priceLogRecord);
        }
    }

    private void writeLogs() {

        // TODO sort by date?

        // TODO concurrency issues

        for (LogRecord l : bidLogRecords.toArray(new LogRecord[bidLogRecords.size()])) {
            writeLineToCSV(l.getLine(), bidlogFile);
        }

        // TODO you'll lose events this way
        bidLogRecords.clear();

        LOGGER.info("CSVLogger [{}] wrote to {}", loggerId, bidlogFile);

        for (LogRecord l : priceLogRecords.toArray(new LogRecord[priceLogRecords.size()])) {
            writeLineToCSV(l.getLine(), priceLogFile);
        }

        priceLogRecords.clear();
        LOGGER.info("CSVLogger [{}] wrote to [{}]", loggerId, priceLogFile);

    }

    /**
     * Activate the component.
     * 
     * @param properties
     *            updated configuration properties
     */
    @Activate
    public synchronized void activate(Map<String, Object> properties) {

        Config config = Configurable.createConfigurable(Config.class, properties);
        processConfig(properties);

        scheduledFuture = scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                writeLogs();
            }
        }, 0, config.logUpdateRate(), TimeUnit.SECONDS);

        LOGGER.info("CSVLogger [{}], activated", loggerId);
    }

    /**
     * Deactivates the component
     */
    @Deactivate
    public void deactivate() {

        scheduledFuture.cancel(false);

        LOGGER.info("CSVLogger [{}], deactivated", loggerId);
    }

    /**
     * Handle configuration modifications.
     * 
     * @param properties
     *            updated configuration properties
     */
    @Modified
    public synchronized void modified(Map<String, Object> properties) {

        // TODO what to do when properties change? Filter could be okay, but the rest would mess everything up.
        processConfig(properties);
    }

    @Override
    @Reference(dynamic = true, multiple = true, optional = true)
    public void addObservable(ObservableAgent observable, Map<String, Object> properties) {
        super.addObservable(observable, properties);
    }

    // TODO this method only calls the superclass. is it missing an annotation?

    @Override
    protected List<String> filter() {
        return this.filter;
    }

    private void processConfig(Map<String, Object> properties) {
        Config config = Configurable.createConfigurable(Config.class, properties);

        this.filter = config.filter();

        // ConfigAdmin will sometimes generate a filter with 1 empty element. Ignore it.
        if (filter != null && !filter.isEmpty() && filter.get(0).isEmpty()) {
            this.filter = new ArrayList<String>();
        }

        this.separator = config.separator();
        this.loggerId = config.loggerId();

        // TODO what to do with invalid pattern?
        this.dateFormat = new SimpleDateFormat(config.dateFormat());

        this.priceLogFile = new File(config.logLocation() + File.separator + config.pricelogFilenamePattern());

        if (!priceLogFile.exists()) {
            writeLineToCSV(PRICE_HEADER_ROW, priceLogFile);
        }

        this.bidlogFile = new File(config.logLocation() + File.separator + config.bidlogFilenamePattern());

        if (!bidlogFile.exists()) {
            writeLineToCSV(BID_HEADER_ROW, bidlogFile);
        }

        updateObservables();
    }

    private void writeLineToCSV(String[] line, File outputFile) {

        try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outputFile, true)))) {
            for (String s : line) {
                out.print(s + separator);
            }
            out.println();

        } catch (IOException e) {
            // TODO do something with this exception
            LOGGER.error(e.getMessage());
        }

    }

    @Reference
    public void setScheduler(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    @Reference
    public void setTimeService(TimeService timeService) {
        this.timeService = timeService;
    }

}
