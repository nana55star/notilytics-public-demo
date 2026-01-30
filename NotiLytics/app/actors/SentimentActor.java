package actors;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import models.SentimentJson;
import models.ReadabilityJson;

/**
 * Actor responsible for the Individual Task (d): Article Sentiment in Phase 2.
 *
 * <p>This actor is designed to run asynchronously. It receives raw JSON data from the
 * News API, calculates the sentiment using the logic defined in {@link models.SentimentJson},
 * and replies with the enriched JSON containing happy/sad emoticons.</p>
 *
 * @author zahra ebrahimizadehghahrood
 */
public class SentimentActor extends AbstractBehavior<SentimentActor.Command> {

    // --- Messages (What this actor can receive) ---
    public interface Command {}

    public static final class AnalyzeSentiment implements Command {
        public final String rawJson;
        public final ActorRef<SentimentResult> replyTo;


        /**
         * Constructor for the AnalyzeSentiment command.
         *
         * @param rawJson The raw JSON string received from the News Service.
         * @param replyTo The actor waiting for the result  
         */
        public AnalyzeSentiment(String rawJson, ActorRef<SentimentResult> replyTo) {
            this.rawJson = rawJson;
            this.replyTo = replyTo;
        }
    }

    // Result Message  
    public static final class SentimentResult {
        public final String enrichedJson;

        public SentimentResult(String enrichedJson) {
            this.enrichedJson = enrichedJson;
        }
    }

    // --- Factory Method  to create the behavior of this actor. 
    public static Behavior<Command> create() {
        return Behaviors.setup(SentimentActor::new);
    }

    // --- Constructor ---
    private SentimentActor(ActorContext<Command> context) {
        super(context);
    }

    // --- Message Handling   ---
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(AnalyzeSentiment.class, this::onAnalyzeSentiment)
                .build();
    }


    /**
     * Handles the {@link AnalyzeSentiment} command.
     * <p>Delegates the actual logic to the helper class {@link SentimentJson} to ensure
     * consistency with Phase 1 logic, then sends the result back to the requester.</p>
     *
     * @param command The incoming analysis request.
     * @return The behavior to handle the next message (same behavior).
     */
    private Behavior<Command> onAnalyzeSentiment(AnalyzeSentiment command) {
        //  Perform the Phase 1 logic (delegating to  existing helper)
        String jsonWithSentiment = SentimentJson.addSentimentToNewsApiJson(command.rawJson);
        String withReadability = ReadabilityJson.addReadabilityToNewsApiJson(jsonWithSentiment);

        // Send the result back to whoever asked (SearchStreamActor)
        command.replyTo.tell(new SentimentResult(withReadability));

    
        return this;
    }
}
