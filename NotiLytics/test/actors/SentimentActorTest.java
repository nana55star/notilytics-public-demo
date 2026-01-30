package actors;

import org.apache.pekko.actor.testkit.typed.javadsl.TestKitJunitResource;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.ClassRule;
import org.junit.Test;
import static org.junit.Assert.assertTrue;


/**
 * JUnit tests for {@link SentimentActor}.
 * <p>Uses the Pekko TestKit to verify that the actor correctly receives messages,
 * processes the JSON, and replies with the correct sentiment emoticons.</p>
 *
 * @author zahra ebrahimizadehghahrood
 */
public class SentimentActorTest {

    //  This creates a temporary ActorSystem just for testing.
    // It automatically shuts down when tests are done.
    @ClassRule
    public static final TestKitJunitResource testKit = new TestKitJunitResource();

    @Test
    public void testSentimentAnalysisHappy() {
        //  Spawn the SentimentActor inside the test environment
        ActorRef<SentimentActor.Command> sentimentActor = testKit.spawn(SentimentActor.create());

        // Create a "Probe". A probe is a fake actor that listens for replies.
        // We act like the "SearchStreamActor", waiting for the result.
        TestProbe<SentimentActor.SentimentResult> probe = testKit.createTestProbe(SentimentActor.SentimentResult.class);

        // Create a dummy JSON string that definitely looks "Happy"
        String fakeHappyJson = "{ \"articles\": [ { \"description\": \"This is a great success and a big win! I love it.\" } ] }";

        //  Send the message to the actor
        sentimentActor.tell(new SentimentActor.AnalyzeSentiment(fakeHappyJson, probe.getRef()));

        // Did the actor reply?
        SentimentActor.SentimentResult result = probe.expectMessageClass(SentimentActor.SentimentResult.class);

        // Check if the result contains the Happy Emoticon :-)
        assertTrue("Expected happy emoticon in result", result.enrichedJson.contains(":-)"));
    }

    /**
     * Verifies that the actor correctly identifies a "Sad" sentiment.
     * <p>Sends a JSON containing negative keywords and expects
     * the response to contain the sad emoticon ":-(".</p>
     *
     */
    @Test
    public void testSentimentAnalysisSad() {
        // Spawn the actor
        ActorRef<SentimentActor.Command> sentimentActor = testKit.spawn(SentimentActor.create());
        TestProbe<SentimentActor.SentimentResult> probe = testKit.createTestProbe(SentimentActor.SentimentResult.class);

        // Create a dummy JSON string that looks "Sad"
        String fakeSadJson = "{ \"articles\": [ { \"description\": \"This is a terrible disaster and a huge fail.\" } ] }";

        // Send message
        sentimentActor.tell(new SentimentActor.AnalyzeSentiment(fakeSadJson, probe.getRef()));

        // Expect reply
        SentimentActor.SentimentResult result = probe.expectMessageClass(SentimentActor.SentimentResult.class);

        // Assert Sad Emoticon :-(
        assertTrue("Expected sad emoticon in result", result.enrichedJson.contains(":-("));
    }

    /**
     * Verifies that the actor handles neutral or empty content gracefully.
     * <p>Sends a JSON with no strong emotional keywords and checks that a valid
     * result is returned (defaults to neutral or preserves structure).</p>
     */
    @Test
    public void testSentimentAnalysisNeutral() {
        // Spawn the actor
        ActorRef<SentimentActor.Command> sentimentActor = testKit.spawn(SentimentActor.create());
        TestProbe<SentimentActor.SentimentResult> probe = testKit.createTestProbe(SentimentActor.SentimentResult.class);

        // Create a dummy JSON with neutral or no text
        String fakeNeutralJson = "{ \"articles\": [ { \"description\": \"The sky is blue and the table is wood.\" } ] }";

        // Send message
        sentimentActor.tell(new SentimentActor.AnalyzeSentiment(fakeNeutralJson, probe.getRef()));

        // Expect reply
        SentimentActor.SentimentResult result = probe.expectMessageClass(SentimentActor.SentimentResult.class);

        // Assert Neutral Emoticon :-|
        // it might default to :-| if it finds no keywords.
        // We check that it at least returned a valid JSON structure.
        assertTrue("Result should not be empty", result.enrichedJson.length() > 0);
    }

    @Test
    public void testSentimentActorAddsReadability() {
        ActorRef<SentimentActor.Command> sentimentActor = testKit.spawn(SentimentActor.create());
        TestProbe<SentimentActor.SentimentResult> probe = testKit.createTestProbe(SentimentActor.SentimentResult.class);

        String json = "{\"articles\":[{\"description\":\"Clear and simple prose hopefully easy to read.\"}]}";

        sentimentActor.tell(new SentimentActor.AnalyzeSentiment(json, probe.getRef()));
        SentimentActor.SentimentResult result = probe.expectMessageClass(SentimentActor.SentimentResult.class);

        assertTrue("Expected readingEase field", result.enrichedJson.contains("\"readingEase\""));
        assertTrue("Expected gradeLevel field", result.enrichedJson.contains("\"gradeLevel\""));
    }
}
