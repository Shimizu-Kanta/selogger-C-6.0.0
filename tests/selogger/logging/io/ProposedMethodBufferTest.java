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
	public void testFreqAndRecordAfterTrim() throws Exception {
		ProposedMethodBuffer buf =
				new ProposedMethodBuffer(int.class, 10, PrometObjectRecordingStrategy.Strong);

		for (int i = 0; i < 10; i++) {
			buf.addInt(i, i, 1);
		}

		Assert.assertEquals(10L, buf.count());
		Assert.assertEquals(10, buf.size());

		buf.trimOldEvents(2);

		// trimしても累積発生回数 freq は減らない
		Assert.assertEquals(10L, buf.count());

		// 保持件数 record だけ減る
		Assert.assertEquals(8, buf.size());

		buf.addInt(10, 10, 1);

		Assert.assertEquals(11L, buf.count());
		Assert.assertEquals(9, buf.size());

		JsonNode root = toJson(buf);
		Assert.assertEquals(11L, root.get("freq").asLong());
		Assert.assertEquals(9, root.get("record").asInt());

		Assert.assertEquals(9, root.get("value").size());
		Assert.assertEquals(2, root.get("value").get(0).asInt());
		Assert.assertEquals(10, root.get("value").get(8).asInt());
	}

	@Test
	public void testRingBufferOrderWithoutTrim() throws Exception {
		ProposedMethodBuffer buf =
				new ProposedMethodBuffer(int.class, 4, PrometObjectRecordingStrategy.Strong);

		for (int i = 0; i < 7; i++) {
			buf.addInt(i, i, 1);
		}

		Assert.assertEquals(7L, buf.count());
		Assert.assertEquals(4, buf.size());

		JsonNode root = toJson(buf);
		Assert.assertEquals(7L, root.get("freq").asLong());
		Assert.assertEquals(4, root.get("record").asInt());

		// 最新4件が古い順に 3,4,5,6 として出ること
		Assert.assertEquals(3, root.get("value").get(0).asInt());
		Assert.assertEquals(4, root.get("value").get(1).asInt());
		Assert.assertEquals(5, root.get("value").get(2).asInt());
		Assert.assertEquals(6, root.get("value").get(3).asInt());

		Assert.assertEquals(3, root.get("seqnum").get(0).asLong());
		Assert.assertEquals(6, root.get("seqnum").get(3).asLong());
	}

	@Test
	public void testTrimKeepsLogicalOrderAfterRingBufferWrapped() throws Exception {
		ProposedMethodBuffer buf =
				new ProposedMethodBuffer(int.class, 4, PrometObjectRecordingStrategy.Strong);

		for (int i = 0; i < 7; i++) {
			buf.addInt(i, i, 1);
		}

		// 論理的には [3,4,5,6] を保持している状態。
		// そこから古い2件を削除すると [5,6] が残るはず。
		buf.trimOldEvents(2);

		Assert.assertEquals(7L, buf.count());
		Assert.assertEquals(2, buf.size());

		buf.addInt(7, 7, 1);

		JsonNode root = toJson(buf);
		Assert.assertEquals(8L, root.get("freq").asLong());
		Assert.assertEquals(3, root.get("record").asInt());

		Assert.assertEquals(5, root.get("value").get(0).asInt());
		Assert.assertEquals(6, root.get("value").get(1).asInt());
		Assert.assertEquals(7, root.get("value").get(2).asInt());
	}
}