package selogger.logging.io;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import selogger.logging.io.ProposedMethodLogger.PrometObjectRecordingStrategy;

/**
 * global trim の prefix sum 化（タスクB）で計算結果が変わっていないことを確認するテスト。
 *
 * <p>{@link PrometTrimReference} が旧実装、{@link ProposedMethodLogger} が新実装。
 * cap の値だけでなく、trim 後の各 buffer の保持件数列まで完全一致することを見る。</p>
 */
public class ProposedMethodTrimEquivalenceTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** 新実装（累積和 + O(1) 評価）の findGlobalCap を、生のサイズ列から呼ぶ。 */
	private static int findGlobalCapPrefix(int[] sizes, int targetTotal) {
		int[] sorted = sizes.clone();
		Arrays.sort(sorted);
		long[] prefixSums = new long[sorted.length + 1];
		for (int i = 0; i < sorted.length; i++) {
			prefixSums[i + 1] = prefixSums[i] + sorted[i];
		}
		return ProposedMethodLogger.findGlobalCap(sorted, prefixSums, targetTotal);
	}

	private static void assertSameCap(String label, int[] sizes, int targetTotal) {
		int expected = PrometTrimReference.findGlobalCapLinear(sizes, targetTotal);
		int actual = findGlobalCapPrefix(sizes, targetTotal);
		if (expected != actual) {
			Assert.fail(label + ": cap mismatch for targetTotal=" + targetTotal
					+ " expected=" + expected + " actual=" + actual
					+ " sizes=" + summarize(sizes));
		}
	}

	private static String summarize(int[] sizes) {
		if (sizes.length <= 20) return Arrays.toString(sizes);
		return "n=" + sizes.length + " head=" + Arrays.toString(Arrays.copyOf(sizes, 20)) + "...";
	}

	private static long sum(int[] sizes) {
		long total = 0;
		for (int s: sizes) total += s;
		return total;
	}

	/**
	 * 1 つのサイズ列に対して、意味のある targetTotal を一通り試す。
	 * @return 実際に検証したケース数
	 */
	private static int assertSameCapForManyTargets(String label, int[] sizes) {
		long total = sum(sizes);
		long[] targets = {
				-1L,
				0L,
				1L,
				total / 4,
				total / 2,
				(total * 4) / 5,       // leaverate=80 相当
				total - 1,
				total,
				total + 1,
				total + 1000,
		};
		int checked = 0;
		for (long t: targets) {
			int target = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, t));
			assertSameCap(label, sizes, target);
			checked++;
		}
		return checked;
	}

	@Test
	public void testFindGlobalCapMatchesLinearImplementationOnEdgeCases() {
		assertSameCapForManyTargets("empty", new int[0]);
		assertSameCapForManyTargets("single-zero", new int[] { 0 });
		assertSameCapForManyTargets("single-one", new int[] { 1 });
		assertSameCapForManyTargets("single-large", new int[] { 100000 });
		assertSameCapForManyTargets("all-zero", new int[2000]);

		int[] allEqual = new int[2000];
		Arrays.fill(allEqual, 777);
		assertSameCapForManyTargets("all-equal", allEqual);

		int[] allMax = new int[1000];
		Arrays.fill(allMax, 100000);
		assertSameCapForManyTargets("all-max", allMax);

		// 極端な偏り: 1 件だけ巨大で残りは 0 / 1。
		int[] skewed = new int[2000];
		Arrays.fill(skewed, 1);
		skewed[0] = 100000;
		assertSameCapForManyTargets("skewed-one-huge", skewed);

		int[] skewedZero = new int[2000];
		skewedZero[1999] = 100000;
		assertSameCapForManyTargets("skewed-one-huge-rest-zero", skewedZero);

		// 半分が 0、半分が最大値。
		int[] bimodal = new int[2000];
		for (int i = 1000; i < 2000; i++) bimodal[i] = 100000;
		assertSameCapForManyTargets("bimodal", bimodal);

		// 階段状。
		int[] staircase = new int[2000];
		for (int i = 0; i < 2000; i++) staircase[i] = i * 50;
		assertSameCapForManyTargets("staircase", staircase);
	}

	@Test
	public void testFindGlobalCapMatchesLinearImplementationOnRandomInputs() {
		Random random = new Random(20260907L);
		int cases = 0;

		// 要素数 0〜2000、値域 0〜100,000。分布を変えながら 10,000 ケース以上を検証する。
		for (int iteration = 0; iteration < 1200; iteration++) {
			int n = random.nextInt(2001);
			int[] sizes = new int[n];

			int shape = iteration % 6;
			for (int i = 0; i < n; i++) {
				switch (shape) {
				case 0: // 一様
					sizes[i] = random.nextInt(100001);
					break;
				case 1: // 小さい値に偏る
					sizes[i] = random.nextInt(50);
					break;
				case 2: // 大きい値に偏る
					sizes[i] = 100000 - random.nextInt(50);
					break;
				case 3: // 全同値
					sizes[i] = 4242;
					break;
				case 4: // 全 0
					sizes[i] = 0;
					break;
				default: // 少数の外れ値を含む
					sizes[i] = (random.nextInt(100) == 0) ? random.nextInt(100001) : random.nextInt(10);
					break;
				}
			}
			// 極端な偏りを混ぜる。
			if (n > 0 && iteration % 3 == 0) {
				sizes[random.nextInt(n)] = 100000;
			}

			cases += assertSameCapForManyTargets("random#" + iteration, sizes);

			// ランダムな targetTotal も混ぜる。
			long total = sum(sizes);
			for (int k = 0; k < 3; k++) {
				long bound = Math.min(Integer.MAX_VALUE, total + 10);
				int target = (bound <= 0) ? 0 : (int) (Math.abs(random.nextLong()) % (bound + 1));
				assertSameCap("random#" + iteration, sizes, target);
				cases++;
			}
		}

		Assert.assertTrue("at least 10,000 equivalence cases must be checked, but was " + cases,
				cases >= 10000);
	}

	// ------------------------------------------------------------------
	// trimAllBuffers 全体の同値性
	// ------------------------------------------------------------------

	private ProposedMethodLogger newLogger(String name, int bufferSize, int leaveRate) throws Exception {
		File trace = folder.newFile(name + ".json");
		return new ProposedMethodLogger(trace, bufferSize, leaveRate, false,
				PrometObjectRecordingStrategy.Strong, true, null);
	}

	@SuppressWarnings("unchecked")
	private static ArrayList<ProposedMethodBuffer> getBuffers(ProposedMethodLogger logger) throws Exception {
		Field f = ProposedMethodLogger.class.getDeclaredField("buffers");
		f.setAccessible(true);
		return (ArrayList<ProposedMethodBuffer>) f.get(logger);
	}

	private static int getTotalRecords(ProposedMethodLogger logger) throws Exception {
		Field f = ProposedMethodLogger.class.getDeclaredField("totalRecords");
		f.setAccessible(true);
		return ((Integer) f.get(logger)).intValue();
	}

	private static void invokeTrimAllBuffers(ProposedMethodLogger logger) throws Exception {
		Method m = ProposedMethodLogger.class.getDeclaredMethod("trimAllBuffers");
		m.setAccessible(true);
		m.invoke(logger);
	}

	private static int getTargetTotalRecords(ProposedMethodLogger logger) throws Exception {
		Method m = ProposedMethodLogger.class.getDeclaredMethod("getTargetTotalRecords");
		m.setAccessible(true);
		return ((Integer) m.invoke(logger)).intValue();
	}

	/**
	 * dataId ごとの保持件数を指定して buffer を用意し、trimAllBuffers を実行して
	 * リファレンス実装と保持件数列を比較する。
	 *
	 * @param sizesByDataId dataId をインデックスとした保持件数。負値はその dataId が
	 *                      null（未使用）であることを表す。
	 */
	private void assertTrimAllBuffersMatchesReference(String label, int bufferSize, int leaveRate,
			int[] sizesByDataId) throws Exception {
		ProposedMethodLogger logger = newLogger(label, bufferSize, leaveRate);
		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);

		ArrayList<Integer> presentDataIds = new ArrayList<>();
		ArrayList<Integer> presentSizes = new ArrayList<>();
		for (int dataId = 0; dataId < sizesByDataId.length; dataId++) {
			int size = sizesByDataId[dataId];
			if (size < 0) {
				buffers.add(null);
				continue;
			}
			ProposedMethodBuffer buf = new ProposedMethodBuffer(int.class, bufferSize,
					PrometObjectRecordingStrategy.Strong);
			for (int i = 0; i < size; i++) {
				buf.addInt(i, i, 0);
			}
			Assert.assertEquals(label + ": setup size for dataId " + dataId, size, buf.size());
			buffers.add(buf);
			presentDataIds.add(dataId);
			presentSizes.add(size);
		}

		int n = presentSizes.size();
		int[] sizes = new int[n];
		int[] dataIds = new int[n];
		for (int i = 0; i < n; i++) {
			sizes[i] = presentSizes.get(i).intValue();
			dataIds[i] = presentDataIds.get(i).intValue();
		}

		int targetTotal = getTargetTotalRecords(logger);
		int[] expected = PrometTrimReference.planKeepSizes(sizes, dataIds, targetTotal);

		invokeTrimAllBuffers(logger);

		int[] actual = new int[n];
		for (int i = 0; i < n; i++) {
			actual[i] = buffers.get(dataIds[i]).size();
		}

		Assert.assertArrayEquals(label + ": trimmed size sequence must match the reference implementation"
				+ " (bufferSize=" + bufferSize + ", leaveRate=" + leaveRate
				+ ", targetTotal=" + targetTotal + ", sizes=" + summarize(sizes) + ")",
				expected, actual);

		long expectedTotal = 0;
		for (int e: expected) expectedTotal += e;
		Assert.assertEquals(label + ": totalRecords", (int) expectedTotal, getTotalRecords(logger));
		Assert.assertTrue(label + ": sum(record) must not exceed size", expectedTotal <= bufferSize);
	}

	@Test
	public void testTrimAllBuffersMatchesReferenceOnHandPickedCases() throws Exception {
		assertTrimAllBuffersMatchesReference("hand-simple", 10, 80, new int[] { 8, 2 });
		assertTrimAllBuffersMatchesReference("hand-balanced", 10, 80, new int[] { 4, 4, 2 });
		assertTrimAllBuffersMatchesReference("hand-with-gaps", 20, 80, new int[] { 5, -1, 7, -1, -1, 8 });
		assertTrimAllBuffersMatchesReference("hand-all-equal", 100, 50, new int[] { 20, 20, 20, 20, 20 });
		assertTrimAllBuffersMatchesReference("hand-all-zero", 100, 80, new int[] { 0, 0, 0, 0 });
		assertTrimAllBuffersMatchesReference("hand-single", 100, 80, new int[] { 100 });
		assertTrimAllBuffersMatchesReference("hand-no-trim-needed", 1000, 80, new int[] { 1, 2, 3 });

		// 各 buffer が 1 件でも targetTotal を超える退化ケース（cap = 0 になる）。
		int[] manyOnes = new int[300];
		Arrays.fill(manyOnes, 1);
		assertTrimAllBuffersMatchesReference("hand-degenerate", 100, 80, manyOnes);

		// 余り枠の再配分が大量に走るケース。
		int[] mixed = new int[200];
		for (int i = 0; i < mixed.length; i++) mixed[i] = i % 7;
		assertTrimAllBuffersMatchesReference("hand-remainder-heavy", 400, 80, mixed);
	}

	@Test
	public void testTrimAllBuffersMatchesReferenceOnRandomInputs() throws Exception {
		Random random = new Random(987654321L);

		for (int iteration = 0; iteration < 300; iteration++) {
			int n = 1 + random.nextInt(60);
			int bufferSize = 4 + random.nextInt(2000);
			int leaveRate = 1 + random.nextInt(99);

			int[] sizesByDataId = new int[n];
			for (int i = 0; i < n; i++) {
				if (random.nextInt(6) == 0) {
					sizesByDataId[i] = -1; // dataId の欠番
				} else if (random.nextInt(4) == 0) {
					sizesByDataId[i] = 0;
				} else {
					sizesByDataId[i] = random.nextInt(Math.min(bufferSize, 200) + 1);
				}
			}

			assertTrimAllBuffersMatchesReference("random-trim#" + iteration, bufferSize, leaveRate, sizesByDataId);
		}
	}
}
