package net.powermatcher.core.concentrator;

import java.util.Deque;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import javax.measure.Measure;
import javax.measure.unit.SI;

import net.powermatcher.api.AgentEndpoint;
import net.powermatcher.api.MatcherEndpoint;
import net.powermatcher.api.Session;
import net.powermatcher.api.data.Bid;
import net.powermatcher.api.data.Price;
import net.powermatcher.api.messages.BidUpdate;
import net.powermatcher.api.messages.PriceUpdate;
import net.powermatcher.api.monitoring.ObservableAgent;
import net.powermatcher.core.BaseAgentEndpoint;
import net.powermatcher.core.BaseMatcherEndpoint;
import net.powermatcher.core.auctioneer.Auctioneer;
import net.powermatcher.core.bidcache.AggregatedBid;

import org.flexiblepower.context.FlexiblePowerContext;

import aQute.bnd.annotation.component.Activate;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Deactivate;
import aQute.bnd.annotation.metatype.Configurable;
import aQute.bnd.annotation.metatype.Meta;

/**
 * <p>
 * This class represents a {@link Concentrator} component where several instances can be created.
 * </p>
 *
 * <p>
 * The {@link Concentrator} receives {@link Bid} from the agents and forwards this in an aggregate {@link Bid} up in the
 * hierarchy to a {@link Concentrator} or to the {@link Auctioneer}. It will receive price updates from the
 * {@link Auctioneer} and forward them to its connected agents.
 *
 * @author FAN
 * @version 2.0
 */
@Component(designateFactory = Concentrator.Config.class, immediate = true,
           provide = { AgentEndpoint.class, ObservableAgent.class, MatcherEndpoint.class })
public class Concentrator
    extends BaseAgentEndpoint
    implements MatcherEndpoint {

    @Meta.OCD
    public static interface Config {
        @Meta.AD(deflt = "concentrator")
        String agentId();

        @Meta.AD(deflt = "auctioneer")
        String desiredParentId();

        @Meta.AD(deflt = "60", description = "Number of seconds between bid updates")
        long bidUpdateRate();
    }

    private static final int MAX_BIDS = 900;

    private final BaseMatcherEndpoint matcherPart = new BaseMatcherEndpoint() {
    };

    /**
     * The schedule that is running the bid updates. This is created in the {@link #activate(Map)} method and cancelled
     * in the {@link #deactivate()} method.
     */
    private ScheduledFuture<?> bidUpdateSchedule;

    protected Config config;

    /**
     * OSGi calls this method to activate a managed service.
     *
     * @param properties
     *            the configuration properties
     */
    @Activate
    public void activate(final Map<String, ?> properties) {
        activate(Configurable.createConfigurable(Config.class, properties));
    }

    /**
     * Convenient activate method that takes a {@link Config} object. This also makes subclassing easier.
     *
     * @param config
     *            The {@link Config} object that configures this concentrator
     */
    public void activate(Config config) {
        this.config = config;
        matcherPart.activate(config.agentId());
        activate(config.agentId(), config.desiredParentId());

        Hashtable<String, Object> properties = new Hashtable<String, Object>();
        properties.put("agentId", config.agentId());

        LOGGER.info("Concentrator [{}], activated", config.agentId());
    }

    @Override
    public void setContext(FlexiblePowerContext context) {
        super.setContext(context);
        matcherPart.setContext(context);

        Runnable command = new Runnable() {
            @Override
            public void run() {
                try {
                    doBidUpdate();
                } catch (RuntimeException e) {
                    LOGGER.error("doBidUpate failed for Concentrator " + config.agentId(), e);
                }
            }

        };

        bidUpdateSchedule = context.scheduleAtFixedRate(command,
                                                        Measure.valueOf(0, SI.SECOND),
                                                        Measure.valueOf(config.bidUpdateRate(), SI.SECOND));
    }

    /**
     * OSGi calls this method to deactivate a managed service.
     */
    @Override
    @Deactivate
    public void deactivate() {
        bidUpdateSchedule.cancel(false);
        LOGGER.info("Concentrator [{}], deactivated", config.agentId());
    }

    void doBidUpdate() {
        if (matcherPart.isInitialized()) {
            AggregatedBid aggregatedBid = matcherPart.aggregate();
            Bid bid = transformBid(aggregatedBid);
            BidUpdate bidUpdate = publishBid(bid);
            saveBid(aggregatedBid, bidUpdate);
        }
    }

    @Override
    public void connectToMatcher(Session session) {
        super.connectToMatcher(session);
        matcherPart.configure(session.getMarketBasis(), session.getClusterId());
    }

    @Override
    public synchronized void matcherEndpointDisconnected(Session session) {
        matcherPart.unconfigure();
        super.matcherEndpointDisconnected(session);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handlePriceUpdate(PriceUpdate priceUpdate) {
        super.handlePriceUpdate(priceUpdate);

        try {
            SentBidInformation info = retreiveAggregatedBid(priceUpdate.getBidNumber());
            Price price = transformPrice(priceUpdate.getPrice(), info);
            matcherPart.publishPrice(price, info.getOriginalBid());
        } catch (IllegalArgumentException ex) {
            LOGGER.warn("Received a price update for a bid that I never sent, id: {}", priceUpdate.getBidNumber());
        }
    }

    /**
     * This method should be overridden when the bid that will be sent has to be changed.
     *
     * @param aggregatedBid
     *            The (input) aggregated bid as calculated normally (the sum of all the bids of the agents).
     * @return The bid that will be sent to the matcher that is connected to this {@link Concentrator}.
     */
    protected Bid transformBid(Bid aggregatedBid) {
        return aggregatedBid;
    }

    /**
     * This method should be overridden when the price that will be sent down has to be changed. This is called just
     * before the price will be sent down to the connected agents.
     *
     * @param price
     *            The input price update as received from the connected matcher.
     * @param aggregatedBid
     *            The {@link AggregatedBid} that has lead to this price update.
     * @return The {@link Price} as it has to be sent to the connected agents.
     */
    protected Price transformPrice(Price price, SentBidInformation info) {
        return price;
    }

    // This part keeps track of send bids to be able to retrieve them later

    private final Deque<SentBidInformation> sentBids = new LinkedList<SentBidInformation>();

    private void saveBid(AggregatedBid aggregatedBid, BidUpdate sentBidUpdate) {
        SentBidInformation info = new SentBidInformation(aggregatedBid, sentBidUpdate);

        synchronized (sentBids) {
            sentBids.add(info);

            if (sentBids.size() > MAX_BIDS) {
                LOGGER.warn("The number of generated bids is becoming very big, possible memory leak?");
                while (sentBids.size() > MAX_BIDS) {
                    sentBids.removeFirst();
                }
            }
        }
    }

    private SentBidInformation retreiveAggregatedBid(int bidNumberReference) {
        synchronized (sentBids) {
            boolean found = false;
            for (SentBidInformation info : sentBids) {
                if (info.getBidNumber() == bidNumberReference) {
                    found = true;
                }
            }

            if (!found) {
                throw new IllegalArgumentException("No bid with bidNumber " + bidNumberReference + " is available");
            }

            while (true) {
                SentBidInformation info = sentBids.peek();
                if (info.getBidNumber() == bidNumberReference) {
                    return info;
                } else {
                    sentBids.removeFirst();
                }
            }
        }
    }

    // These method make sure that we implement the MatcherEndpoint
    // These just call the BaseMatcherEndpoint

    @Override
    public void agentEndpointDisconnected(Session session) {
        matcherPart.agentEndpointDisconnected(session);
    }

    @Override
    public boolean connectToAgent(Session session) {
        return matcherPart.connectToAgent(session);
    }

    @Override
    public void handleBidUpdate(Session session, BidUpdate bidUpdate) {
        matcherPart.handleBidUpdate(session, bidUpdate);
    }
}
