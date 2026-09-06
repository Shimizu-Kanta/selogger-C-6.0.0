package selogger.logging.io;

import java.util.ArrayList;

import org.junit.Assert;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import selogger.logging.io.ProposedMethodLogger.PrometObjectRecordingStrategy;
import selogger.logging.util.JsonBuffer;
import selogger.logging.util.ObjectId;

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

	// ------------------------------------------------------------------
	// trimOldEvents の要素コピーが全要素型で正しいことを確認するテスト群。
	// リングが折り返した状態（addCount > bufferSize）が最も壊れやすいので、
	// どの型についても折り返しあり / なしの両方を通す。
	// ------------------------------------------------------------------

	/** i 番目のイベントを buffer に追加する。 */
	private interface Adder {
		void add(ProposedMethodBuffer buf, int i, long seqnum, int threadId);
	}

	/** JSON に書き出された値が、元の i 番目の値と一致することを検証する。 */
	private interface ValueChecker {
		void check(String message, JsonNode value, int i);
	}

	private static long seqnumOf(int i) {
		return 1000L + i;
	}

	private static int threadOf(int i) {
		return 50 + i;
	}

	/**
	 * bufferSize の buffer に addCount 件追加してから keepSize まで trim し、
	 * 残った値 / seqnum / thread が「新しい側 keepSize 件」と一致することを確認する。
	 *
	 * addCount &gt; bufferSize ならリングが折り返した状態で trim される。
	 */
	private void assertTrimKeepsNewest(String label, Class<?> type, PrometObjectRecordingStrategy strategy,
			int bufferSize, int addCount, int keepSize, Adder adder, ValueChecker checker) throws Exception {
		ProposedMethodBuffer buf = new ProposedMethodBuffer(type, bufferSize, strategy);
		for (int i = 0; i < addCount; i++) {
			adder.add(buf, i, seqnumOf(i), threadOf(i));
		}

		int storedBeforeTrim = Math.min(addCount, bufferSize);
		Assert.assertEquals(label + ": size before trim", storedBeforeTrim, buf.size());
		Assert.assertEquals(label + ": count before trim", (long) addCount, buf.count());

		int removed = buf.trimToSize(keepSize);
		Assert.assertEquals(label + ": removed count", storedBeforeTrim - keepSize, removed);
		Assert.assertEquals(label + ": size after trim", keepSize, buf.size());
		Assert.assertEquals(label + ": count must not decrease by trim", (long) addCount, buf.count());

		JsonNode root = toJson(buf);
		Assert.assertEquals(label + ": freq", (long) addCount, root.get("freq").asLong());
		Assert.assertEquals(label + ": record", keepSize, root.get("record").asInt());
		Assert.assertEquals(label + ": value length", keepSize, root.get("value").size());
		Assert.assertEquals(label + ": seqnum length", keepSize, root.get("seqnum").size());
		Assert.assertEquals(label + ": thread length", keepSize, root.get("thread").size());

		// trim 後に残るのは、追加した中で最も新しい keepSize 件。
		for (int k = 0; k < keepSize; k++) {
			int original = addCount - keepSize + k;
			checker.check(label + ": value[" + k + "]", root.get("value").get(k), original);
			Assert.assertEquals(label + ": seqnum[" + k + "]", seqnumOf(original), root.get("seqnum").get(k).asLong());
			Assert.assertEquals(label + ": thread[" + k + "]", threadOf(original), root.get("thread").get(k).asInt());
		}
	}

	/**
	 * 折り返しなし（addCount &lt;= bufferSize）と折り返しあり（addCount &gt; bufferSize）の
	 * 両方で同じ検証を行う。折り返しありのケースでは、さらに copyRange が 2 区間に
	 * 分割される keepSize を選ぶ。
	 */
	private void assertTrimKeepsNewestBothLayouts(String label, Class<?> type,
			PrometObjectRecordingStrategy strategy, Adder adder, ValueChecker checker) throws Exception {
		// 折り返しなし: 8 件追加して 3 件残す。getPos は恒等写像。
		assertTrimKeepsNewest(label + "/linear", type, strategy, 16, 8, 3, adder, checker);

		// 折り返しあり: bufferSize=8 に 12 件追加すると nextPos=4 でリングが一周している。
		// keepSize=6 だと物理位置 6,7 と 0..3 の 2 区間に分割される。
		assertTrimKeepsNewest(label + "/wrapped-split", type, strategy, 8, 12, 6, adder, checker);

		// 折り返しあり、かつ分割が起きない（末尾側だけで足りる）ケース。
		assertTrimKeepsNewest(label + "/wrapped-single", type, strategy, 8, 12, 3, adder, checker);

		// 何周も折り返したうえで 1 件だけ残す、削除量が最大のケース。
		assertTrimKeepsNewest(label + "/wrapped-trim-to-one", type, strategy, 8, 20, 1, adder, checker);
	}

	@Test
	public void testTrimKeepsNewestValuesForBoolean() throws Exception {
		assertTrimKeepsNewestBothLayouts("boolean", boolean.class, PrometObjectRecordingStrategy.Strong,
				(buf, i, s, t) -> buf.addBoolean(i % 3 == 0, s, t),
				(msg, v, i) -> Assert.assertEquals(msg, i % 3 == 0, v.asBoolean()));
	}

	@Test
	public void testTrimKeepsNewestValuesForByte() throws Exception {
		assertTrimKeepsNewestBothLayouts("byte", byte.class, PrometObjectRecordingStrategy.Strong,
				(buf, i, s, t) -> buf.addByte((byte) (i - 5), s, t),
				(msg, v, i) -> Assert.assertEquals(msg, (byte) (i - 5), (byte) v.asInt()));
	}

	@Test
	public void testTrimKeepsNewestValuesForChar() throws Exception {
		assertTrimKeepsNewestBothLayouts("char", char.class, PrometObjectRecordingStrategy.Strong,
				(buf, i, s, t) -> buf.addChar((char) ('A' + i), s, t),
				(msg, v, i) -> Assert.assertEquals(msg, 'A' + i, v.asInt()));
	}

	@Test
	public void testTrimKeepsNewestValuesForShort() throws Exception {
		assertTrimKeepsNewestBothLayouts("short", short.class, PrometObjectRecordingStrategy.Strong,
				(buf, i, s, t) -> buf.addShort((short) (i * 300 - 1000), s, t),
				(msg, v, i) -> Assert.assertEquals(msg, (short) (i * 300 - 1000), (short) v.asInt()));
	}

	@Test
	public void testTrimKeepsNewestValuesForInt() throws Exception {
		assertTrimKeepsNewestBothLayouts("int", int.class, PrometObjectRecordingStrategy.Strong,
				(buf, i, s, t) -> buf.addInt(i * 7 - 3, s, t),
				(msg, v, i) -> Assert.assertEquals(msg, i * 7 - 3, v.asInt()));
	}

	@Test
	public void testTrimKeepsNewestValuesForLong() throws Exception {
		assertTrimKeepsNewestBothLayouts("long", long.class, PrometObjectRecordingStrategy.Strong,
				(buf, i, s, t) -> buf.addLong((long) i * 1000000007L, s, t),
				(msg, v, i) -> Assert.assertEquals(msg, (long) i * 1000000007L, v.asLong()));
	}

	@Test
	public void testTrimKeepsNewestValuesForFloat() throws Exception {
		assertTrimKeepsNewestBothLayouts("float", float.class, PrometObjectRecordingStrategy.Strong,
				(buf, i, s, t) -> buf.addFloat(i * 0.5f, s, t),
				(msg, v, i) -> Assert.assertEquals(msg, i * 0.5f, (float) v.asDouble(), 0.0f));
	}

	@Test
	public void testTrimKeepsNewestValuesForDouble() throws Exception {
		assertTrimKeepsNewestBothLayouts("double", double.class, PrometObjectRecordingStrategy.Strong,
				(buf, i, s, t) -> buf.addDouble(i * 0.25, s, t),
				(msg, v, i) -> Assert.assertEquals(msg, i * 0.25, v.asDouble(), 0.0));
	}

	@Test
	public void testTrimKeepsNewestValuesForObjectId() throws Exception {
		assertTrimKeepsNewestBothLayouts("ObjectId", ObjectId.class, PrometObjectRecordingStrategy.Id,
				(buf, i, s, t) -> buf.addObjectId(new ObjectId(9000 + i, "java.lang.String", "oid" + i), s, t),
				(msg, v, i) -> {
					Assert.assertEquals(msg + " id", Long.toString(9000 + i), v.get("id").asText());
					Assert.assertEquals(msg + " type", "java.lang.String", v.get("type").asText());
					Assert.assertEquals(msg + " str", "oid" + i, v.get("str").asText());
				});
	}

	@Test
	public void testTrimKeepsNewestValuesForStrongString() throws Exception {
		// new String(...) にして、定数プールの同一化で値の取り違えが隠れないようにする。
		assertTrimKeepsNewestBothLayouts("String/Strong", Object.class, PrometObjectRecordingStrategy.Strong,
				(buf, i, s, t) -> buf.addObject(new String("s" + i), s, t),
				(msg, v, i) -> {
					Assert.assertEquals(msg + " type", "java.lang.String", v.get("type").asText());
					Assert.assertEquals(msg + " str", "s" + i, v.get("str").asText());
				});
	}

	@Test
	public void testTrimKeepsNewestValuesForWeakReference() throws Exception {
		// WeakReference 越しでも trim 後に同じ値が読めることを確認する。
		// 参照先が GC されると <GC> になってしまうので、テスト中は強参照を保持しておく。
		final ArrayList<String> alive = new ArrayList<>();
		assertTrimKeepsNewestBothLayouts("String/Weak", Object.class, PrometObjectRecordingStrategy.Weak,
				(buf, i, s, t) -> {
					String value = new String("w" + i);
					alive.add(value);
					buf.addObject(value, s, t);
				},
				(msg, v, i) -> {
					Assert.assertEquals(msg + " type", "java.lang.String", v.get("type").asText());
					Assert.assertEquals(msg + " str", "w" + i, v.get("str").asText());
				});
		Assert.assertFalse(alive.isEmpty());
	}

	@Test
	public void testTrimOnWrappedRingKeepsLogicalOrder() throws Exception {
		// リングの折り返しを明示的に検証する。
		// bufferSize=8 に 12 件追加すると物理配列は [8,9,10,11,4,5,6,7]、nextPos=4。
		ProposedMethodBuffer buf = new ProposedMethodBuffer(int.class, 8, PrometObjectRecordingStrategy.Strong);
		for (int i = 0; i < 12; i++) {
			buf.addInt(i, i, 0);
		}

		Assert.assertEquals(12L, buf.count());
		Assert.assertEquals(8, buf.size());

		JsonNode before = toJson(buf);
		for (int i = 0; i < 8; i++) {
			Assert.assertEquals("logical order before trim", i + 4, before.get("value").get(i).asInt());
		}

		// keepSize=6 は物理位置 6,7 と 0..3 の 2 区間に分割される折り返しケース。
		Assert.assertEquals(2, buf.trimToSize(6));

		JsonNode after = toJson(buf);
		Assert.assertEquals(12L, after.get("freq").asLong());
		Assert.assertEquals(6, after.get("record").asInt());
		for (int i = 0; i < 6; i++) {
			Assert.assertEquals("logical order after wrapped trim", i + 6, after.get("value").get(i).asInt());
			Assert.assertEquals("seqnum after wrapped trim", i + 6, after.get("seqnum").get(i).asLong());
		}

		// trim 後は非折り返し状態に戻るので、続けて追加しても順序が保たれる。
		buf.addInt(100, 100, 0);
		JsonNode appended = toJson(buf);
		Assert.assertEquals(13L, appended.get("freq").asLong());
		Assert.assertEquals(7, appended.get("record").asInt());
		Assert.assertEquals(100, appended.get("value").get(6).asInt());
	}

	@Test
	public void testRepeatedWrapAndTrimKeepsNewestValues() throws Exception {
		// 折り返し → trim → 再度折り返し、を繰り返しても常に新しい側が残ることを確認する。
		ProposedMethodBuffer buf = new ProposedMethodBuffer(int.class, 8, PrometObjectRecordingStrategy.Strong);
		int next = 0;
		for (int round = 0; round < 5; round++) {
			for (int k = 0; k < 11; k++) {
				buf.addInt(next, next, 0);
				next++;
			}
			buf.trimToSize(5);

			JsonNode root = toJson(buf);
			Assert.assertEquals("record", 5, root.get("record").asInt());
			for (int i = 0; i < 5; i++) {
				Assert.assertEquals("round " + round + " value[" + i + "]",
						next - 5 + i, root.get("value").get(i).asInt());
				Assert.assertEquals("round " + round + " seqnum[" + i + "]",
						next - 5 + i, root.get("seqnum").get(i).asLong());
			}
		}
		Assert.assertEquals((long) next, buf.count());
	}
}
