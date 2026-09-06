package selogger.logging.io;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Random;

import selogger.logging.io.ProposedMethodLogger.PrometObjectRecordingStrategy;

/**
 * promet の効果測定ハーネス。
 *
 * <p>@Test ではなく main を持つクラスにしている。実行に時間がかかるので CI では回さない。
 * JMH は依存を増やしたくないので使わない。</p>
 *
 * <p>使い方:</p>
 * <pre>
 * java -cp target/classes:target/test-classes selogger.logging.io.PrometMeasurementHarness \
 *      --size 150000 --leaverate 80 --kinds 25084 --events 10000000 --zipf 1.0
 * </pre>
 *
 * <p>同じ入力列（同じ乱数種）を keepk=false と keepk=true の両方に流し、
 * trim 回数・保存率・要素コピー数・配列確保数・所要時間を比較する。</p>
 *
 * <p>数値の意味:</p>
 * <ul>
 * <li>trims        : global trim を実際に実行した回数</li>
 * <li>sum_freq     : Sigma freq。発生した全イベント数</li>
 * <li>sum_record   : Sigma record。最終的に保持しているイベント数</li>
 * <li>rate         : 保存率 sum_record / sum_freq</li>
 * <li>max_record   : max_i record_i</li>
 * <li>copied       : trim による要素コピー数（値・seqnum・thread をまとめて 1 件と数える）</li>
 * <li>allocated    : 確保された配列の本数（buffer 生成・配列拡張・trim を含む）</li>
 * <li>elapsed_ms   : 記録ループの所要時間</li>
 * </ul>
 */
public class PrometMeasurementHarness {

	private static final class Result {
		private int trims;
		private long sumFreq;
		private long sumRecord;
		private int maxRecord;
		private int distinctKinds;
		private long copied;
		private long allocated;
		private long elapsedMs;
		private int finalCap;
		private boolean zeroCapReached;
	}

	public static void main(String[] args) throws Exception {
		int size = 150000;
		int leaveRate = 80;
		int kinds = 25084;
		long events = 10000000L;
		double zipf = 1.0;
		long seed = 20260907L;

		for (int i = 0; i < args.length - 1; i++) {
			String key = args[i];
			String value = args[i + 1];
			if (key.equals("--size")) { size = Integer.parseInt(value); i++; }
			else if (key.equals("--leaverate")) { leaveRate = Integer.parseInt(value); i++; }
			else if (key.equals("--kinds")) { kinds = Integer.parseInt(value); i++; }
			else if (key.equals("--events")) { events = Long.parseLong(value); i++; }
			else if (key.equals("--zipf")) { zipf = Double.parseDouble(value); i++; }
			else if (key.equals("--seed")) { seed = Long.parseLong(value); i++; }
		}

		System.out.println("promet measurement harness");
		System.out.println("  size (L)     : " + size);
		System.out.println("  leaverate (r): " + leaveRate);
		System.out.println("  event kinds  : " + kinds);
		System.out.println("  events       : " + events);
		System.out.println("  zipf exponent: " + zipf + (zipf == 0.0 ? " (uniform)" : ""));
		System.out.println("  seed         : " + seed);
		System.out.println();

		// 累積分布は両方の実行で共有する。サンプリングは同じ種の Random で作り直すので
		// keepk の true / false にまったく同じイベント列が流れる。
		double[] cumulative = buildZipfCumulative(kinds, zipf);

		Result withoutKeepK = run(size, leaveRate, kinds, events, seed, cumulative, false);
		Result withKeepK = run(size, leaveRate, kinds, events, seed, cumulative, true);

		printTable(withoutKeepK, withKeepK);
	}

	/**
	 * ランク r（1 始まり）の確率が 1 / r^zipf に比例する累積分布を作る。
	 * zipf == 0 なら一様分布になる。
	 */
	private static double[] buildZipfCumulative(int kinds, double zipf) {
		double[] cumulative = new double[kinds];
		double total = 0;
		for (int i = 0; i < kinds; i++) {
			total += 1.0 / Math.pow(i + 1, zipf);
			cumulative[i] = total;
		}
		for (int i = 0; i < kinds; i++) {
			cumulative[i] /= total;
		}
		return cumulative;
	}

	/** 累積分布から dataId を 1 つ引く。 */
	private static int sample(double[] cumulative, double u) {
		int low = 0;
		int high = cumulative.length - 1;
		while (low < high) {
			int mid = (low + high) >>> 1;
			if (cumulative[mid] < u) low = mid + 1;
			else high = mid;
		}
		return low;
	}

	private static Result run(int size, int leaveRate, int kinds, long events, long seed,
			double[] cumulative, boolean keepK) throws Exception {
		File trace = File.createTempFile("promet-harness-", ".json");
		trace.deleteOnExit();

		ProposedMethodBuffer.enableStats();
		ProposedMethodLogger logger = new ProposedMethodLogger(trace, size, leaveRate, false,
				PrometObjectRecordingStrategy.Strong, true, keepK, null);

		Random random = new Random(seed);
		long start = System.nanoTime();
		for (long i = 0; i < events; i++) {
			int dataId = sample(cumulative, random.nextDouble());
			logger.recordEvent(dataId, (int) i);
		}
		long elapsedNs = System.nanoTime() - start;

		Result r = new Result();
		r.elapsedMs = elapsedNs / 1000000L;
		r.copied = ProposedMethodBuffer.getCopiedRecords();
		r.allocated = ProposedMethodBuffer.getAllocatedArrays();
		ProposedMethodBuffer.disableStats();

		r.trims = logger.getTrimCount();
		r.finalCap = logger.getCurrentCap();
		r.zeroCapReached = logger.isZeroCapReached();

		for (ProposedMethodBuffer b: getBuffers(logger)) {
			if (b == null) continue;
			r.sumFreq += b.count();
			r.sumRecord += b.size();
			r.maxRecord = Math.max(r.maxRecord, b.size());
			if (b.count() > 0) r.distinctKinds++;
		}

		if (r.sumRecord > size) {
			throw new IllegalStateException("invariant violated: sum(record)=" + r.sumRecord + " > size=" + size);
		}
		if (r.sumFreq != events) {
			throw new IllegalStateException("invariant violated: sum(freq)=" + r.sumFreq + " != events=" + events);
		}
		return r;
	}

	@SuppressWarnings("unchecked")
	private static ArrayList<ProposedMethodBuffer> getBuffers(ProposedMethodLogger logger) throws Exception {
		Field f = ProposedMethodLogger.class.getDeclaredField("buffers");
		f.setAccessible(true);
		return (ArrayList<ProposedMethodBuffer>) f.get(logger);
	}

	private static void printTable(Result off, Result on) {
		String fmt = "%-14s %18s %18s%n";
		System.out.printf(fmt, "", "keepk=false", "keepk=true");
		System.out.printf(fmt, "--------------", "------------------", "------------------");
		System.out.printf(fmt, "trims", Integer.toString(off.trims), Integer.toString(on.trims));
		System.out.printf(fmt, "sum_freq", Long.toString(off.sumFreq), Long.toString(on.sumFreq));
		System.out.printf(fmt, "sum_record", Long.toString(off.sumRecord), Long.toString(on.sumRecord));
		System.out.printf(fmt, "rate",
				String.format("%.4f%%", 100.0 * off.sumRecord / off.sumFreq),
				String.format("%.4f%%", 100.0 * on.sumRecord / on.sumFreq));
		System.out.printf(fmt, "max_record", Integer.toString(off.maxRecord), Integer.toString(on.maxRecord));
		System.out.printf(fmt, "kinds_seen", Integer.toString(off.distinctKinds), Integer.toString(on.distinctKinds));
		System.out.printf(fmt, "copied", Long.toString(off.copied), Long.toString(on.copied));
		System.out.printf(fmt, "allocated", Long.toString(off.allocated), Long.toString(on.allocated));
		System.out.printf(fmt, "elapsed_ms", Long.toString(off.elapsedMs), Long.toString(on.elapsedMs));
		System.out.printf(fmt, "final_cap", Integer.toString(off.finalCap), Integer.toString(on.finalCap));
		System.out.printf(fmt, "zero_cap", Boolean.toString(off.zeroCapReached), Boolean.toString(on.zeroCapReached));
	}
}
