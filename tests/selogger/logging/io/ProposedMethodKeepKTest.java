package selogger.logging.io;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Random;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import selogger.logging.io.ProposedMethodLogger.PrometObjectRecordingStrategy;
import selogger.logging.util.JsonBuffer;

/**
 * keepk オプション（global trim で決まった共通上限 k を trim 後も保持する）のテスト。
 *
 * <p>既定は keepk=false で、その場合の挙動は導入前と完全に一致していなければならない。</p>
 */
public class ProposedMethodKeepKTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private int loggerSeq = 0;

	private ProposedMethodLogger newLogger(int bufferSize, int leaveRate, boolean keepK) throws Exception {
		File trace = folder.newFile("trace-" + (loggerSeq++) + ".json");
		return new ProposedMethodLogger(trace, bufferSize, leaveRate, false,
				PrometObjectRecordingStrategy.Strong, true, keepK, null);
	}

	@SuppressWarnings("unchecked")
	private static ArrayList<ProposedMethodBuffer> getBuffers(ProposedMethodLogger logger) throws Exception {
		Field f = ProposedMethodLogger.class.getDeclaredField("buffers");
		f.setAccessible(true);
		return (ArrayList<ProposedMethodBuffer>) f.get(logger);
	}

	private static int sumRecord(ArrayList<ProposedMethodBuffer> buffers) {
		int sum = 0;
		for (ProposedMethodBuffer b: buffers) {
			if (b != null) sum += b.size();
		}
		return sum;
	}

	private static long sumFreq(ArrayList<ProposedMethodBuffer> buffers) {
		long sum = 0;
		for (ProposedMethodBuffer b: buffers) {
			if (b != null) sum += b.count();
		}
		return sum;
	}

	private static JsonNode toJson(ProposedMethodBuffer buf) throws Exception {
		JsonBuffer json = new JsonBuffer();
		json.writeStartObject();
		buf.writeJson(json, false);
		json.writeEndObject();
		return new ObjectMapper().readTree(json.toString());
	}

	// ------------------------------------------------------------------
	// keepk=false（既定）
	// ------------------------------------------------------------------

	@Test
	public void testKeepKDisabledLeavesRetentionLimitAtGlobalCapacity() throws Exception {
		ProposedMethodLogger logger = newLogger(100, 80, false);

		// 10 個の dataId に 10 件ずつ。次の 1 件で global trim が走る。
		for (int i = 0; i < 100; i++) {
			logger.recordEvent(i % 10, i);
		}
		logger.recordEvent(0, 1000);

		Assert.assertEquals("global trim should have run once", 1, logger.getTrimCount());
		Assert.assertEquals("keepk=false keeps currentCap at the global capacity",
				100, logger.getCurrentCap());

		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		for (ProposedMethodBuffer b: buffers) {
			if (b == null) continue;
			Assert.assertEquals("keepk=false must not lower any retention limit",
					100, b.getRetentionLimit());
		}
	}

	@Test
	public void testKeepKDisabledStillGrowsBuffersAfterTrim() throws Exception {
		ProposedMethodLogger logger = newLogger(100, 80, false);

		for (int i = 0; i < 100; i++) {
			logger.recordEvent(i % 10, i);
		}
		// trim: [10 x 10] -> cap=8 -> [8 x 10] = 80
		logger.recordEvent(0, 1000);

		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		Assert.assertEquals(9, buffers.get(0).size());

		// keepk=false では上限が 100 のままなので、さらに追記すると保持件数が増える。
		logger.recordEvent(0, 1001);
		Assert.assertEquals(10, buffers.get(0).size());
	}

	// ------------------------------------------------------------------
	// keepk=true
	// ------------------------------------------------------------------

	@Test
	public void testKeepKAppliesCommonCapToEveryBufferAfterTrim() throws Exception {
		ProposedMethodLogger logger = newLogger(20, 80, true);

		// dataId 0..3 に 5 件ずつ。totalRecords = 20。
		for (int i = 0; i < 20; i++) {
			logger.recordEvent(i % 4, i);
		}
		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		Assert.assertEquals(0, logger.getTrimCount());
		Assert.assertEquals(20, sumRecord(buffers));

		// 次の 1 件で trim。targetTotal = 16、[5,5,5,5] -> cap=4 -> [4,4,4,4]。
		logger.recordEvent(0, 100);

		Assert.assertEquals(1, logger.getTrimCount());
		Assert.assertEquals("common cap k", 4, logger.getCurrentCap());
		for (int dataId = 0; dataId < 4; dataId++) {
			Assert.assertEquals("retention limit of dataId " + dataId,
					4, buffers.get(dataId).getRetentionLimit());
		}

		// dataId 0 は trim 後 4 件 [4,8,12,16] になり、そこへ 100 が入って最古が上書きされる。
		Assert.assertEquals(4, buffers.get(0).size());
		JsonNode b0 = toJson(buffers.get(0));
		Assert.assertEquals(6L, b0.get("freq").asLong());
		Assert.assertEquals(4, b0.get("record").asInt());
		Assert.assertEquals(8, b0.get("value").get(0).asInt());
		Assert.assertEquals(12, b0.get("value").get(1).asInt());
		Assert.assertEquals(16, b0.get("value").get(2).asInt());
		Assert.assertEquals(100, b0.get("value").get(3).asInt());
	}

	@Test
	public void testKeepKOverwritesOldestInsteadOfGrowing() throws Exception {
		ProposedMethodLogger logger = newLogger(20, 80, true);

		for (int i = 0; i < 20; i++) {
			logger.recordEvent(i % 4, i);
		}
		logger.recordEvent(0, 100); // triggers trim; cap becomes 4

		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		int recordBefore = sumRecord(buffers);
		int trimCountBefore = logger.getTrimCount();

		// 上限に達した buffer への追記は保持件数を増やさない（最古が上書きされるだけ）。
		for (int i = 0; i < 200; i++) {
			logger.recordEvent(i % 4, 1000 + i);
			Assert.assertEquals("size must stay at the common cap",
					4, buffers.get(i % 4).size());
		}

		Assert.assertEquals("total records must not grow", recordBefore, sumRecord(buffers));
		Assert.assertEquals("no further global trim is needed", trimCountBefore, logger.getTrimCount());

		// freq は増え続ける。
		Assert.assertEquals(221L, sumFreq(buffers));

		// 最新の 4 件が残っている。dataId 0 には i=0,4,...,196 が入った。
		JsonNode b0 = toJson(buffers.get(0));
		Assert.assertEquals(4, b0.get("record").asInt());
		for (int k = 0; k < 4; k++) {
			Assert.assertEquals(1000 + 184 + k * 4, b0.get("value").get(k).asInt());
		}
	}

	@Test
	public void testKeepKLimitsDataIdsThatFirstAppearAfterTrim() throws Exception {
		ProposedMethodLogger logger = newLogger(100, 80, true);

		for (int i = 0; i < 100; i++) {
			logger.recordEvent(i % 10, i);
		}
		Assert.assertEquals(0, logger.getTrimCount());

		// この 1 件目で trim が走り、その後で dataId 50 の buffer が新規作成される。
		for (int i = 0; i < 12; i++) {
			logger.recordEvent(50, 5000 + i);
		}

		Assert.assertEquals(1, logger.getTrimCount());
		Assert.assertEquals(8, logger.getCurrentCap());

		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		ProposedMethodBuffer newBuffer = buffers.get(50);
		Assert.assertNotNull(newBuffer);
		Assert.assertEquals("a dataId first seen after trim is also limited by k",
				8, newBuffer.getRetentionLimit());
		Assert.assertEquals(8, newBuffer.size());
		Assert.assertEquals(12L, newBuffer.count());

		JsonNode json = toJson(newBuffer);
		for (int k = 0; k < 8; k++) {
			Assert.assertEquals(5000 + 4 + k, json.get("value").get(k).asInt());
		}
	}

	@Test
	public void testKeepKReducesTrimCountForTheSameInputSequence() throws Exception {
		int bufferSize = 100;
		int leaveRate = 80;
		int events = 10000;

		ProposedMethodLogger withoutKeepK = newLogger(bufferSize, leaveRate, false);
		ProposedMethodLogger withKeepK = newLogger(bufferSize, leaveRate, true);

		for (int i = 0; i < events; i++) {
			withoutKeepK.recordEvent(i % 10, i);
			withKeepK.recordEvent(i % 10, i);
		}

		Assert.assertTrue("keepk=true must not trim more often than keepk=false"
				+ " (with=" + withKeepK.getTrimCount() + ", without=" + withoutKeepK.getTrimCount() + ")",
				withKeepK.getTrimCount() <= withoutKeepK.getTrimCount());
		Assert.assertTrue("keepk=false should trim repeatedly", withoutKeepK.getTrimCount() > 100);
		Assert.assertEquals("keepk=true should stop trimming once k is applied",
				1, withKeepK.getTrimCount());

		// freq はどちらでも全イベントを数える。
		Assert.assertEquals((long) events, sumFreq(getBuffers(withKeepK)));
		Assert.assertEquals((long) events, sumFreq(getBuffers(withoutKeepK)));
	}

	@Test
	public void testGlobalRecordSumStaysUnderCapacityInBothModes() throws Exception {
		int bufferSize = 250;
		int leaveRate = 70;

		for (int mode = 0; mode < 2; mode++) {
			boolean keepK = (mode == 1);
			ProposedMethodLogger logger = newLogger(bufferSize, leaveRate, keepK);
			ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);

			// 出現頻度に偏りのある dataId 列。新しい dataId が途中から現れる。
			Random random = new Random(4242L + mode);
			long expectedFreq = 0;
			for (int i = 0; i < 60000; i++) {
				int dataId;
				int r = random.nextInt(100);
				if (r < 60) {
					dataId = random.nextInt(3);            // 高頻度な少数
				} else if (r < 95) {
					dataId = 3 + random.nextInt(60);       // 中頻度
				} else {
					dataId = 63 + (i / 500);               // 途中から現れる dataId
				}
				logger.recordEvent(dataId, i);
				expectedFreq++;

				Assert.assertTrue("keepK=" + keepK + ": sum(record) must never exceed size at event " + i,
						sumRecord(buffers) <= bufferSize);
			}

			Assert.assertEquals("keepK=" + keepK + ": every event must be counted in freq",
					expectedFreq, sumFreq(buffers));

			// keepK 有効時は共通上限がすべての buffer に効いている。
			if (keepK) {
				int cap = logger.getCurrentCap();
				for (ProposedMethodBuffer b: buffers) {
					if (b == null) continue;
					Assert.assertTrue("retention limit must be at most cap+1 (cap=" + cap
							+ ", limit=" + b.getRetentionLimit() + ")",
							b.getRetentionLimit() <= cap + 1);
					Assert.assertTrue("size must not exceed the retention limit",
							b.size() <= b.getRetentionLimit());
				}
			}
		}
	}

	@Test
	public void testKeepKKeepsNewestValuesAcrossRepeatedOverwrites() throws Exception {
		// 上限に達したまま長く回しても、常に最新のイベントが残ることを確認する。
		ProposedMethodLogger logger = newLogger(40, 50, true);

		for (int i = 0; i < 5000; i++) {
			logger.recordEvent(i % 8, i);
		}

		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		Assert.assertTrue(sumRecord(buffers) <= 40);
		Assert.assertEquals(5000L, sumFreq(buffers));

		for (int dataId = 0; dataId < 8; dataId++) {
			ProposedMethodBuffer b = buffers.get(dataId);
			JsonNode json = toJson(b);
			int record = json.get("record").asInt();
			Assert.assertEquals(record, json.get("value").size());

			// dataId に流れた最後の値は 4992 + dataId（i % 8 == dataId となる最大の i）。
			int newest = 4992 + dataId;
			for (int k = 0; k < record; k++) {
				int expected = newest - (record - 1 - k) * 8;
				Assert.assertEquals("dataId " + dataId + " value[" + k + "]",
						expected, json.get("value").get(k).asInt());
				Assert.assertTrue("seqnum must be increasing",
						k == 0 || json.get("seqnum").get(k).asLong() > json.get("seqnum").get(k - 1).asLong());
			}
		}
	}

	@Test
	public void testSetRetentionLimitRejectsValuesBelowStoredSize() {
		ProposedMethodBuffer buf = new ProposedMethodBuffer(int.class, 10, PrometObjectRecordingStrategy.Strong);
		for (int i = 0; i < 6; i++) {
			buf.addInt(i, i, 0);
		}

		// storedSize を下回る上限は受け付けず、storedSize まで引き上げて適用する。
		buf.setRetentionLimit(2);
		Assert.assertEquals(6, buf.getRetentionLimit());
		Assert.assertEquals(6, buf.size());

		// 上限ちょうどまで下げると、以降の追記は最古を上書きする。
		buf.addInt(100, 100, 0);
		Assert.assertEquals(6, buf.size());
		Assert.assertEquals(7L, buf.count());
	}

	@Test
	public void testSetRetentionLimitKeepsLogicalOrderWhenRingIsWrapped() throws Exception {
		// 折り返した状態で上限を変えると、リングの法が変わって順序が壊れやすい。
		ProposedMethodBuffer buf = new ProposedMethodBuffer(int.class, 8, PrometObjectRecordingStrategy.Strong);
		for (int i = 0; i < 12; i++) {
			buf.addInt(i, i, 0);
		}
		Assert.assertEquals(8, buf.size());

		// 上限を上げる（storedSize == 旧上限 なので折り返している）。
		buf.setRetentionLimit(20);
		Assert.assertEquals(20, buf.getRetentionLimit());

		JsonNode json = toJson(buf);
		Assert.assertEquals(8, json.get("record").asInt());
		for (int i = 0; i < 8; i++) {
			Assert.assertEquals("logical order must survive a retention limit change",
					i + 4, json.get("value").get(i).asInt());
			Assert.assertEquals(i + 4, json.get("seqnum").get(i).asLong());
		}

		// 上限を上げたので、追記すると保持件数が増える。
		buf.addInt(999, 999, 0);
		json = toJson(buf);
		Assert.assertEquals(9, json.get("record").asInt());
		Assert.assertEquals(999, json.get("value").get(8).asInt());
	}

	@Test
	public void testSaveWithResetClearsCommonCap() throws Exception {
		ProposedMethodLogger logger = newLogger(20, 80, true);
		for (int i = 0; i < 21; i++) {
			logger.recordEvent(i % 4, i);
		}
		Assert.assertEquals(4, logger.getCurrentCap());

		logger.save(true);

		Assert.assertEquals("resetting the trace also clears the common cap",
				20, logger.getCurrentCap());

		// リセット後の dataId は上限なしで伸びる。
		for (int i = 0; i < 6; i++) {
			logger.recordEvent(7, i);
		}
		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		Assert.assertEquals(6, buffers.get(7).size());
		Assert.assertEquals(20, buffers.get(7).getRetentionLimit());
	}
}
