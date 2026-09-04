package selogger.logging.io;

import org.junit.Assert;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import selogger.logging.io.ProposedMethodLogger.PrometObjectRecordingStrategy;
import selogger.logging.util.JsonBuffer;

public class ProposedMethodBufferTest {

	private JsonNode toJson(ProposedMethodBuffer buf) throws Exception {
		JsonBuffer json = new JsonBuffer();
		json.writeStartObject();
		buf.writeJson(json, false);
		json.writeEndObject();
		return new ObjectMapper().readTree(json.toString());
	}

	@Test
	public void testTrimToSizeKeepsNewestEventsAndFreq() throws Exception {
		ProposedMethodBuffer buf = new ProposedMethodBuffer(int.class, 10, PrometObjectRecordingStrategy.Strong);

		for (int i = 0; i < 8; i++) {
			buf.addInt(i, i, 0);
		}

		Assert.assertEquals(8L, buf.count());
		Assert.assertEquals(8, buf.size());

		int removed = buf.trimToSize(5);

		Assert.assertEquals(3, removed);
		Assert.assertEquals("freq/count must not decrease by trim", 8L, buf.count());
		Assert.assertEquals("record/size must decrease by trim", 5, buf.size());

		JsonNode root = toJson(buf);
		Assert.assertEquals(8L, root.get("freq").asLong());
		Assert.assertEquals(5, root.get("record").asInt());
		Assert.assertEquals(5, root.get("value").size());
		Assert.assertEquals(5, root.get("seqnum").size());
		Assert.assertEquals(5, root.get("thread").size());

		// Oldest three values 0,1,2 are removed. Newest five values remain in order.
		for (int i = 0; i < 5; i++) {
			Assert.assertEquals(i + 3, root.get("value").get(i).asInt());
			Assert.assertEquals(i + 3, root.get("seqnum").get(i).asLong());
			Assert.assertEquals(0, root.get("thread").get(i).asInt());
		}
	}

	@Test
	public void testTrimToSizeDoesNothingWhenKeepSizeIsLargerThanCurrentSize() throws Exception {
		ProposedMethodBuffer buf = new ProposedMethodBuffer(int.class, 10, PrometObjectRecordingStrategy.Strong);

		for (int i = 0; i < 3; i++) {
			buf.addInt(i, i, 0);
		}

		int removed = buf.trimToSize(5);

		Assert.assertEquals(0, removed);
		Assert.assertEquals(3L, buf.count());
		Assert.assertEquals(3, buf.size());

		JsonNode root = toJson(buf);
		Assert.assertEquals(3L, root.get("freq").asLong());
		Assert.assertEquals(3, root.get("record").asInt());
		Assert.assertEquals(0, root.get("value").get(0).asInt());
		Assert.assertEquals(1, root.get("value").get(1).asInt());
		Assert.assertEquals(2, root.get("value").get(2).asInt());
	}

	@Test
	public void testTrimToZeroKeepsFreqAndDropsRecord() throws Exception {
		ProposedMethodBuffer buf = new ProposedMethodBuffer(int.class, 10, PrometObjectRecordingStrategy.Strong);

		for (int i = 0; i < 4; i++) {
			buf.addInt(i, i, 0);
		}

		int removed = buf.trimToSize(0);

		Assert.assertEquals(4, removed);
		Assert.assertEquals(4L, buf.count());
		Assert.assertEquals(0, buf.size());

		JsonNode root = toJson(buf);
		Assert.assertEquals(4L, root.get("freq").asLong());
		Assert.assertEquals(0, root.get("record").asInt());
		Assert.assertNull("value array is omitted when record is zero", root.get("value"));
	}
}
