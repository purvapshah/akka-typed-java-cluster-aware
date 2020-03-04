package cluster;

import akka.actor.Address;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.TimerScheduler;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import akka.cluster.Member;
import akka.cluster.MemberStatus;
import akka.cluster.typed.Cluster;
import com.fasterxml.jackson.annotation.JsonCreator;
import org.slf4j.Logger;
import scala.Option;

import java.io.Serializable;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

class ClusterAwareActor {
    private final ActorContext<Message> context;
    private final PingStatistics pingStatistics = new PingStatistics();
    private final ActorRef<HttpServer.PingStatistics> httpServerActor;
    private Set<ActorRef<Message>> serviceInstances;
    private Duration tickInterval = Duration.ofMillis(500);

    static final ServiceKey<Message> serviceKey = ServiceKey.create(Message.class, ClusterAwareActor.class.getSimpleName());

    private ClusterAwareActor(ActorContext<Message> context, ActorRef<HttpServer.PingStatistics> httpServerActor) {
        this.context = context;
        this.httpServerActor = httpServerActor;
    }

    static Behavior<Message> create(ActorRef<HttpServer.PingStatistics> httpServerActor) {
        return Behaviors.setup(context ->
                Behaviors.withTimers(timers ->
                        new ClusterAwareActor(context, httpServerActor).behavior(context, timers)));
    }

    private Behavior<Message> behavior(ActorContext<Message> context, TimerScheduler<Message> timers) {
        receptionistRegisterSubscribe(context);
        timers.startTimerAtFixedRate(Tick.Instance, tickInterval);

        return Behaviors.receive(Message.class)
                .onMessage(Listeners.class, this::onListeners)
                .onMessage(Tick.class, notUsed -> onTick())
                .onMessage(Ping.class, this::onPing)
                .onMessage(Pong.class, this::onPong)
                .build();
    }

    private void receptionistRegisterSubscribe(ActorContext<Message> context) {
        final ActorRef<Receptionist.Listing> listingActorRef = context.messageAdapter(Receptionist.Listing.class, Listeners::new);

        context.getSystem().receptionist()
                .tell(Receptionist.register(serviceKey, context.getSelf()));
        context.getSystem().receptionist()
                .tell(Receptionist.subscribe(serviceKey, listingActorRef));
    }

    private Behavior<Message> onListeners(Listeners listeners) {
        serviceInstances = listeners.listing.getServiceInstances(serviceKey);
        pingStatistics.clearOfflineNodeCounters(serviceInstances);

        log().info("Cluster aware actors subscribers changed, count {}", serviceInstances.size());
        serviceInstances
                .forEach(new Consumer<ActorRef<Message>>() {
                    int i = 0;

                    @Override
                    public void accept(ActorRef<Message> messageActorRef) {
                        log().info("{} {}{}", ++i, self(messageActorRef), messageActorRef);
                    }

                    private String self(ActorRef<Message> clusterAwareActorRef) {
                        return clusterAwareActorRef.equals(context.getSelf()) ? "(SELF) " : "";
                    }
                });

        return Behaviors.same();
    }

    private Behavior<Message> onTick() {
        pingUpColleagues();
        httpServerActor.tell(new HttpServer.PingStatistics(pingStatistics.totalPings, pingStatistics.nodePings));

        return Behaviors.same();
    }

    private Behavior<Message> onPing(Ping ping) {
        log().info("<=={}", ping);
        ping.replyTo.tell(new Pong(context.getSelf(), ping.start));
        pingStatistics.ping(ping.replyTo);
        return Behaviors.same();
    }

    private Behavior<Message> onPong(Pong pong) {
        log().info("<--{}", pong);
        return Behaviors.same();
    }

    private void pingUpColleagues() {
        if (iAmUp()) {
            final int size = serviceInstances.size() - 1;
            log().info("Tick, ping {}", Math.max(size, 0));

            final List<Address> upMembers = getUpMembers();

            serviceInstances.stream()
                    .filter(clusterAwareActorRef -> !clusterAwareActorRef.equals(context.getSelf()))
                    .filter(clusterAwareActorRef -> upMembers.contains(clusterAwareActorRef.path().address()))
                    .forEach(clusterAwareActorRef -> clusterAwareActorRef.tell(new Ping(context.getSelf(), System.nanoTime())));
        } else {
            log().info("Tick, no pings, this node is not up, {}", Cluster.get(context.getSystem()).selfMember());
        }
    }

    private boolean iAmUp() {
        return Cluster.get(context.getSystem()).selfMember().status().equals(MemberStatus.up());
    }

    private List<Address> getUpMembers() {
        final Iterable<Member> members = Cluster.get(context.getSystem()).state().getMembers();
        return StreamSupport.stream(members.spliterator(), false)
                .filter(member -> MemberStatus.up().equals(member.status()))
                .map(Member::address)
                .collect(Collectors.toList());
    }

    private Logger log() {
        return context.getLog();
    }

    public interface Message {
    }

    private static class Listeners implements Message {
        final Receptionist.Listing listing;

        private Listeners(Receptionist.Listing listing) {
            this.listing = listing;
        }
    }

    public static class Ping implements Message, Serializable {
        public final ActorRef<Message> replyTo;
        public final long start;

        @JsonCreator
        public Ping(ActorRef<Message> replyTo, long start) {
            this.replyTo = replyTo;
            this.start = start;
        }

        @Override
        public String toString() {
            return String.format("%s[%s]", getClass().getSimpleName(), replyTo.path());
        }
    }

    public static class Pong implements Message, Serializable {
        public final ActorRef<Message> replyFrom;
        public final long pingStart;

        @JsonCreator
        public Pong(ActorRef<Message> replyFrom, long pingStart) {
            this.replyFrom = replyFrom;
            this.pingStart = pingStart;
        }

        @Override
        public String toString() {
            return String.format("%s[%s, %,dns]", getClass().getSimpleName(), replyFrom.path(), System.nanoTime() - pingStart);
        }
    }

    enum Tick implements Message {
        Instance
    }

    static class PingStatistics {
        int totalPings = 0;
        Map<Integer, Integer> nodePings = new HashMap<>();

        PingStatistics() {
            IntStream.rangeClosed(2551, 2559).forEach(p -> nodePings.put(p, 0));
        }

        void ping(ActorRef<Message> actorRef) {
            ++totalPings;

            final int port = actorRefPort(actorRef);
            if (port >= 2551 && port <= 2559) {
                nodePings.put(port, 1 + nodePings.getOrDefault(port, 0));
            }
        }

        void clearOfflineNodeCounters(Set<ActorRef<Message>> serviceInstances) {
            final List<Integer> ports = new ArrayList<>();
            IntStream.rangeClosed(2551, 2559).forEach(ports::add);

            serviceInstances.forEach(actorRef -> ports.removeIf(p -> p == actorRefPort(actorRef)));
            ports.forEach(port -> nodePings.replace(port, 0));
        }

        private static int actorRefPort(ActorRef<Message> actorRef) {
            final Option<Object> port = actorRef.path().address().port();
            return port.isDefined()
                    ? Integer.parseInt(port.get().toString())
                    : -1;
        }
    }
}
