package org.acme.vehiclerouting.sightline;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/** Turns the backend journal on in dev/test builds and feeds it warnings and errors from the log. */
@IfBuildProfile(anyOf = { "dev", "test" })
@ApplicationScoped
public class SightlineStartup {

    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() < Level.WARNING.intValue()) {
                return;
            }
            String message = record.getMessage();
            try {
                if (record.getParameters() != null && message != null) {
                    message = String.format(message.replace("{}", "%s"), record.getParameters());
                }
            } catch (RuntimeException e) {
                // keep the raw message
            }
            BackendJournal.record("log", "level", record.getLevel().getName(), "logger", record.getLoggerName(),
                    "message", message, "thrown", record.getThrown() == null ? null : record.getThrown().toString());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    void onStart(@Observes StartupEvent event) {
        BackendJournal.enable();
        Logger.getLogger("").addHandler(handler);
        BackendJournal.record("server.started");
    }

    void onStop(@Observes ShutdownEvent event) {
        Logger.getLogger("").removeHandler(handler);
    }
}
