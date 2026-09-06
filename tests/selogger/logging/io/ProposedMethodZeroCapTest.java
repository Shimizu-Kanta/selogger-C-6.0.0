package selogger.logging.io;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import selogger.logging.IErrorLogger;
import selogger.logging.io.ProposedMethodLogger.PrometObjectRecordingStrategy;

/**
 * 退化ケース（共通上限 k が 0 になる）の扱いのテスト。
 *
 * <p>abortonzerok が既定の false なら従来どおり保存を継続し、警告は 1 回だけ出す。
 * true ならトレースの保存を諦め、freq の集計だけを続ける。</p>
 */
public class ProposedMethodZeroCapTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private int loggerSeq = 0;

	/** IErrorLogger に届いたメッセージを溜めるだけのロガー。 */
	private static class RecordingErrorLogger implements IErrorLogger {
		private final List<String> messages = new ArrayList<>();
		private final List<Throwable> throwables = new ArrayList<>();

		@Override
		public void log(Throwable t) {
			throwables.add(t);
		}

		@Override
		public void log(String msg) {
			messages.add(msg);
		}

		@Override
		public void close() {
		}
	}

	private ProposedMethodLogger newLogger(int bufferSize, int leaveRate, boolean abortOnZeroCap,
			IErrorLogger errorLogger) throws Exception {
		File trace = folder.newFile("trace-" + (loggerSeq++) + ".json");
		return new ProposedMethodLogger(trace, bufferSize, leaveRate, false,
				PrometObjectRecordingStrategy.Strong, true, false, abortOnZeroCap, errorLogger);
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

	/**
	 * イベント種類数 n が全体容量 L を大きく上回る列を流し込む。
	 * 1 種類あたり 1 件すら保持できないので、必ず cap == 0 になる。
	 *
	 * @return 流し込んだイベント数
	 */
	private static int feedDegenerateSequence(ProposedMethodLogger logger, int eventKinds, int rounds) {
		int events = 0;
		for (int round = 0; round < rounds; round++) {
			for (int dataId = 0; dataId < eventKinds; dataId++) {
				logger.recordEvent(dataId, round * eventKinds + dataId);
				events++;
			}
		}
		return events;
	}

	// ------------------------------------------------------------------
	// abortonzerok=false（既定）
	// ------------------------------------------------------------------

	@Test
	public void testZeroCapWithoutAbortKeepsRecordingAndWarnsOnce() throws Exception {
		RecordingErrorLogger errors = new RecordingErrorLogger();
		ProposedMethodLogger logger = newLogger(100, 80, false, errors);

		int events = feedDegenerateSequence(logger, 400, 5);

		Assert.assertTrue("the degenerate case must have been reached", logger.isZeroCapReached());
		Assert.assertFalse("recording must not be aborted by default", logger.isTracingAborted());
		Assert.assertTrue("global trim must have run repeatedly", logger.getTrimCount() > 1);

		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		Assert.assertTrue("recording continues by default", sumRecord(buffers) > 0);
		Assert.assertTrue("sum(record) must not exceed size", sumRecord(buffers) <= 100);
		Assert.assertEquals("freq must count every event", (long) events, sumFreq(buffers));

		Assert.assertEquals("the warning must be reported exactly once, not on every trim",
				1, errors.messages.size());
		Assert.assertTrue("throwables must not be logged", errors.throwables.isEmpty());

		String msg = errors.messages.get(0);
		// n は検出時点で記録対象になっていたイベント種類数。L=100 の容量が 1 種類 1 件で
		// 埋まった時点で cap が 0 になるので、この列では n=100 で検出される。
		Assert.assertTrue("the message must contain the event kind count n: " + msg, msg.contains("n=100"));
		Assert.assertTrue("the message must contain the capacity L: " + msg, msg.contains("L=100"));
		Assert.assertTrue("the message must mention abortonzerok: " + msg, msg.contains("abortonzerok"));
	}

	@Test
	public void testZeroCapWithoutAbortToleratesNullErrorLogger() throws Exception {
		// IErrorLogger が null でも落ちないこと。
		ProposedMethodLogger logger = newLogger(100, 80, false, null);
		feedDegenerateSequence(logger, 400, 3);
		Assert.assertTrue(logger.isZeroCapReached());
		Assert.assertFalse(logger.isTracingAborted());
	}

	// ------------------------------------------------------------------
	// abortonzerok=true
	// ------------------------------------------------------------------

	@Test
	public void testZeroCapWithAbortStopsRecordingButKeepsCountingFreq() throws Exception {
		RecordingErrorLogger errors = new RecordingErrorLogger();
		ProposedMethodLogger logger = newLogger(100, 80, true, errors);

		int events = feedDegenerateSequence(logger, 400, 5);

		Assert.assertTrue(logger.isZeroCapReached());
		Assert.assertTrue("recording must be aborted", logger.isTracingAborted());

		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		Assert.assertEquals("no values may be stored after the abort", 0, sumRecord(buffers));
		Assert.assertEquals("freq must keep counting every event", (long) events, sumFreq(buffers));

		Assert.assertEquals("the error must be reported exactly once", 1, errors.messages.size());
		String msg = errors.messages.get(0);
		Assert.assertTrue("the message must contain the event kind count n: " + msg, msg.contains("n=100"));
		Assert.assertTrue("the message must contain the capacity L: " + msg, msg.contains("L=100"));

		// dataId ごとの freq も正しい。5 ラウンドずつ流した。
		for (int dataId = 0; dataId < 400; dataId++) {
			ProposedMethodBuffer b = buffers.get(dataId);
			Assert.assertNotNull("dataId " + dataId, b);
			Assert.assertEquals("freq of dataId " + dataId, 5L, b.count());
			Assert.assertEquals("record of dataId " + dataId, 0, b.size());
		}
	}

	@Test
	public void testAbortAlsoCountsDataIdsFirstSeenAfterTheAbort() throws Exception {
		RecordingErrorLogger errors = new RecordingErrorLogger();
		ProposedMethodLogger logger = newLogger(100, 80, true, errors);

		feedDegenerateSequence(logger, 400, 3);
		Assert.assertTrue(logger.isTracingAborted());

		// 停止後に初めて現れた dataId も freq だけは数える。
		for (int i = 0; i < 7; i++) {
			logger.recordEvent(9999, i);
		}
		// 参照型のイベントでも同じ。
		for (int i = 0; i < 3; i++) {
			logger.recordEvent(9998, "value" + i);
		}

		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		Assert.assertEquals(7L, buffers.get(9999).count());
		Assert.assertEquals(0, buffers.get(9999).size());
		Assert.assertEquals(3L, buffers.get(9998).count());
		Assert.assertEquals(0, buffers.get(9998).size());
		Assert.assertEquals("still nothing stored", 0, sumRecord(buffers));
	}

	@Test
	public void testAbortReportsErrorOnlyOnceAcrossManyEvents() throws Exception {
		RecordingErrorLogger errors = new RecordingErrorLogger();
		ProposedMethodLogger logger = newLogger(100, 80, true, errors);

		feedDegenerateSequence(logger, 400, 50);

		Assert.assertEquals(1, errors.messages.size());
		Assert.assertTrue(logger.isTracingAborted());
	}

	// ------------------------------------------------------------------
	// 出力
	// ------------------------------------------------------------------

	@Test
	public void testZeroCapReachedAppearsInJsonOnlyWhenItHappened() throws Exception {
		// 退化しない通常の実行では、追加フィールドは出力されない。
		ProposedMethodLogger normal = newLogger(1000, 80, false, null);
		for (int i = 0; i < 50; i++) {
			normal.recordEvent(i % 5, i);
		}
		normal.close();
		String normalJson = readTrace(normal);
		Assert.assertTrue("format field must be unchanged", normalJson.startsWith("{ \"format\":\"promet\", \"events\": ["));
		Assert.assertFalse("no extra field when the cap never became 0", normalJson.contains("zeroCapReached"));

		// 退化したら zeroCapReached が付く。既存キーは変えない。
		ProposedMethodLogger degenerate = newLogger(100, 80, false, null);
		feedDegenerateSequence(degenerate, 400, 3);
		degenerate.close();
		String degenerateJson = readTrace(degenerate);
		Assert.assertTrue("zeroCapReached must be added at the top level",
				degenerateJson.startsWith("{ \"format\":\"promet\", \"zeroCapReached\":true, \"events\": ["));

		// abort した場合も同じフィールドが付く。
		ProposedMethodLogger aborted = newLogger(100, 80, true, null);
		feedDegenerateSequence(aborted, 400, 3);
		aborted.close();
		String abortedJson = readTrace(aborted);
		Assert.assertTrue(abortedJson.contains("\"zeroCapReached\":true"));

		// このテストは weaver を通していないので DataInfo が 1 件も登録されておらず、
		// events 配列は常に空になる。イベント単位の freq/record の中身は
		// buffer を直接見る他のテストと、エージェントを通した実行で確認している。
		Assert.assertTrue(normalJson.endsWith("\n]}"));
		Assert.assertTrue(degenerateJson.endsWith("\n]}"));
		Assert.assertTrue(abortedJson.endsWith("\n]}"));
	}

	@Test
	public void testAbortedBufferWritesFreqAndZeroRecordInJson() throws Exception {
		// buffer 単位の JSON（events 配列の各要素の中身）を直接確認する。
		ProposedMethodLogger logger = newLogger(100, 80, true, null);
		feedDegenerateSequence(logger, 400, 3);
		Assert.assertTrue(logger.isTracingAborted());

		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		selogger.logging.util.JsonBuffer json = new selogger.logging.util.JsonBuffer();
		json.writeStartObject();
		buffers.get(0).writeJson(json, false);
		json.writeEndObject();
		String s = json.toString();

		Assert.assertTrue("freq must still be written: " + s, s.contains("\"freq\":3"));
		Assert.assertTrue("record must be 0: " + s, s.contains("\"record\":0"));
		Assert.assertFalse("no values are written after the abort: " + s, s.contains("\"value\""));
		Assert.assertFalse("no seqnum is written after the abort: " + s, s.contains("\"seqnum\""));
	}

	private String readTrace(ProposedMethodLogger logger) throws Exception {
		Field f = ProposedMethodLogger.class.getDeclaredField("traceFile");
		f.setAccessible(true);
		File trace = (File) f.get(logger);
		byte[] bytes = java.nio.file.Files.readAllBytes(trace.toPath());
		return new String(bytes, "UTF-8");
	}

	@Test
	public void testReleaseStorageKeepsCountAndDropsRecords() {
		ProposedMethodBuffer buf = new ProposedMethodBuffer(int.class, 32, PrometObjectRecordingStrategy.Strong);
		for (int i = 0; i < 20; i++) {
			buf.addInt(i, i, 0);
		}
		Assert.assertEquals(20, buf.size());

		buf.releaseStorage();

		Assert.assertEquals("records are dropped", 0, buf.size());
		Assert.assertEquals("freq is kept", 20L, buf.count());

		buf.countOnly();
		buf.countOnly();
		Assert.assertEquals("countOnly increments freq without storing", 22L, buf.count());
		Assert.assertEquals(0, buf.size());
	}
}
