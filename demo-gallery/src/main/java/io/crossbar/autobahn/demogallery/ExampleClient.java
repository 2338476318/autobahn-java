package io.crossbar.autobahn.demogallery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import io.crossbar.autobahn.wamp.Client;
import io.crossbar.autobahn.wamp.Session;
import io.crossbar.autobahn.wamp.auth.AnonymousAuth;
import io.crossbar.autobahn.wamp.interfaces.IAuthenticator;
import io.crossbar.autobahn.wamp.interfaces.ITransport;
import io.crossbar.autobahn.wamp.types.CallResult;
import io.crossbar.autobahn.wamp.types.CloseDetails;
import io.crossbar.autobahn.wamp.types.ExitInfo;
import io.crossbar.autobahn.wamp.types.InvocationDetails;
import io.crossbar.autobahn.wamp.types.InvocationResult;
import io.crossbar.autobahn.wamp.types.Publication;
import io.crossbar.autobahn.wamp.types.Registration;
import io.crossbar.autobahn.wamp.types.SessionDetails;
import io.crossbar.autobahn.wamp.types.Subscription;

public class ExampleClient {

    private static final Logger LOGGER = Logger.getLogger(ExampleClient.class.getName());

    public CompletableFuture<ExitInfo> main(String websocketURL, String realm) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Session session = new Session(executor);
        session.addOnConnectListener(this::onConnectCallback);
        session.addOnJoinListener(this::onJoinCallback);
        session.addOnLeaveListener(this::onLeaveCallback);
        session.addOnDisconnectListener(this::onDisconnectCallback);

        // Now create a transport list to try and add transports to it.
        // In our case, we currently only have Netty based WAMP-over-WebSocket.
        List<ITransport> transports = new ArrayList<>();
        try {
            transports.add(selectTransport(websocketURL));
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Now provide a list of authentication methods.
        // We only support anonymous auth currently.
        List<IAuthenticator> authenticators = new ArrayList<>();
        authenticators.add(new AnonymousAuth());

        // finally, provide everything to a Client instance and connect
        Client client = new Client(transports, executor);
        client.add(session, realm, authenticators);
        return client.connect();
    }

    // Convenience method to dynamically return a transport based on the underlying platform.
    // No rocket science here.
    // Could easily replace all the underlying code with just
    // return new AndroidWebSocket(webSocketURL);
    // If the underlying platform is known upfront.
    private ITransport selectTransport(String webSocketURL) throws Exception {
        Class<?> transportClass;
        if (Objects.equals(System.getProperty("java.vendor"), "The Android Project")) {
            transportClass = Class.forName("io.crossbar.autobahn.wamp.transports.AndroidWebSocket");
        } else {
            transportClass = Class.forName("io.crossbar.autobahn.wamp.transports.NettyTransport");
        }
        return (ITransport) transportClass.getConstructor(String.class).newInstance(webSocketURL);
    }

    private void onConnectCallback(Session session) {
        LOGGER.info("Session connected, ID=" + session.getID());
    }

    private void onJoinCallback(Session session, SessionDetails details) {
        CompletableFuture<Registration> regFuture = session.register(
                "com.example.add2", this::add2, null);
        regFuture.thenAccept(
                registration -> LOGGER.info("Registered procedure: com.example.add2"));

        CompletableFuture<Subscription> subFuture = session.subscribe(
                "com.example.oncounter", this::onCounter, null);
        subFuture.thenAccept(subscription ->
                LOGGER.info(String.format("Subscribed to topic: %s", subscription.topic)));

        final int[] x = {0};
        final int[] counter = {0};

        List<Object> args = new ArrayList<>();
        args.add(x[0]);
        args.add(3);

        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleAtFixedRate(() -> {

            // here we CALL every second
            CompletableFuture<CallResult> f =
                    session.call("com.example.add2", args, null, null);

            f.thenAccept(result -> {
                LOGGER.info(String.format("Got result: %s, ", result.results.get(0)));
                x[0] += 1;
                args.set(0, x[0]);
            });

            f.exceptionally(throwable -> {
                LOGGER.info(String.format("ERROR - call failed: %s", throwable.getMessage()));
                return null;
            });

            CompletableFuture<Publication> p = session.publish(
                    "com.example.oncounter", counter[0], session.getID(), "Java");
            p.thenAccept(publication -> {
                LOGGER.info("published to 'oncounter' with counter " + counter[0]);
                counter[0] += 1;
            });

        }, 0, 2, TimeUnit.SECONDS);
    }

    private void onLeaveCallback(Session session, CloseDetails closeDetails) {
        LOGGER.info(String.format("Left reason=%s, message=%s",
                closeDetails.reason, closeDetails.message));
    }

    private void onDisconnectCallback(Session session, boolean wasClean) {
        LOGGER.info(String.format("Session with ID=%s, disconnected.", session.getID()));
    }

    private CompletableFuture<InvocationResult> add2(List<Object> args, InvocationDetails details) {
        int res = (int) args.get(0) + (int) args.get(1);
        List<Object> arr = new ArrayList<>();
        arr.add(res);
        arr.add(details.session.getID());
        arr.add("Java");
        return CompletableFuture.completedFuture(new InvocationResult(arr));
    }

    private void onCounter(List<Object> args) {
        LOGGER.info(String.format(
                "oncounter event, counter value=%s from component %s (%s)",
                args.get(0), args.get(1), args.get(2)));
    }
}
