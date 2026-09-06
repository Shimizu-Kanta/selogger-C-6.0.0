package selogger.logging.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * prefix sum 化する前の promet global trim を、buffer に依存しない形へ書き写した
 * リファレンス実装。
 *
 * <p>ProposedMethodLogger の現行実装は、cap の二分探索を累積和による O(1) 評価へ
 * 置き換えている。その置き換えで計算結果が 1 件も変わっていないことを確認するために、
 * 旧アルゴリズムをここに保存しておく。ロジックは意図的に旧コードのままで、
 * 「全 buffer を走査する」部分だけを「int 配列を走査する」に読み替えている。</p>
 *
 * <p>本番コードからは参照されない。テスト専用。</p>
 */
class PrometTrimReference {

	private PrometTrimReference() {
	}

	/**
	 * 旧 findGlobalCap。
	 * 二分探索の各ステップで全要素を走査して sum(min(size_i, mid)) を求める O(n log maxSize) 版。
	 *
	 * @param sizes       各 buffer の保持件数（順序は問わない）
	 * @param targetTotal trim 後に全体で残すイベント数
	 * @return sum(min(size_i, cap)) &lt;= targetTotal を満たす最大の cap
	 */
	static int findGlobalCapLinear(int[] sizes, int targetTotal) {
		int maxSize = 0;
		for (int s: sizes) {
			maxSize = Math.max(maxSize, s);
		}

		int low = 0;
		int high = maxSize;
		while (low < high) {
			int mid = (low + high + 1) / 2;

			long cappedTotal = 0;
			for (int s: sizes) {
				cappedTotal += Math.min(s, mid);
			}

			if (cappedTotal <= targetTotal) {
				low = mid;
			} else {
				high = mid - 1;
			}
		}
		return low;
	}

	/**
	 * 旧 trimAllBuffers の計画部分。
	 *
	 * <p>sizes / dataIds は「非 null な buffer を dataId 昇順に並べたもの」であること。
	 * 戻り値は同じ並びでの trim 後の保持件数。</p>
	 *
	 * @param sizes       各 buffer の trim 前の保持件数
	 * @param dataIds     各 buffer の dataId
	 * @param targetTotal trim 後に全体で残すイベント数
	 * @return trim 後の各 buffer の保持件数（入力と同じ並び）
	 */
	static int[] planKeepSizes(int[] sizes, int[] dataIds, int targetTotal) {
		long total = 0;
		for (int s: sizes) {
			total += s;
		}
		int totalRecords = (total > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) total;

		// 旧実装の早期 return と同じ。trim しないので保持件数は変わらない。
		if (totalRecords <= targetTotal) {
			return sizes.clone();
		}

		int cap = findGlobalCapLinear(sizes, targetTotal);

		ArrayList<Plan> plans = new ArrayList<>();
		int plannedTotal = 0;
		for (int i = 0; i < sizes.length; i++) {
			int keepSize = Math.min(sizes[i], cap);
			plans.add(new Plan(dataIds[i], i, sizes[i], keepSize));
			plannedTotal += keepSize;
		}

		int remainingBudget = targetTotal - plannedTotal;
		Collections.sort(plans, new Comparator<Plan>() {
			@Override
			public int compare(Plan a, Plan b) {
				int bySize = Integer.compare(a.originalSize, b.originalSize);
				if (bySize != 0) return bySize;
				return Integer.compare(a.dataId, b.dataId);
			}
		});

		for (Plan p: plans) {
			if (remainingBudget <= 0) break;

			if (p.originalSize > p.keepSize) {
				p.keepSize++;
				remainingBudget--;
			}
		}

		int[] result = new int[sizes.length];
		for (Plan p: plans) {
			result[p.inputIndex] = p.keepSize;
		}
		return result;
	}

	private static class Plan {
		private final int dataId;
		private final int inputIndex;
		private final int originalSize;
		private int keepSize;

		private Plan(int dataId, int inputIndex, int originalSize, int keepSize) {
			this.dataId = dataId;
			this.inputIndex = inputIndex;
			this.originalSize = originalSize;
			this.keepSize = keepSize;
		}
	}
}
