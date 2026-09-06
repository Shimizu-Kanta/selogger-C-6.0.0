package selogger.logging.io;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

import selogger.logging.IErrorLogger;
import selogger.logging.IEventLogger;
import selogger.logging.util.JsonBuffer;
import selogger.logging.util.ObjectId;
import selogger.logging.util.ObjectIdMap;
import selogger.logging.util.ThreadId;
import selogger.weaver.DataInfo;

/**
 * 全バッファ合計の保持件数を size で制御する promet モード用ロガー。
 *
 * 方針:
 * - freq は dataId ごとの累積発生回数として保持する。
 * - record は dataId ごとの現在保持件数として出力する。
 * - bufferSize は「各 dataId の上限」ではなく「全 dataId 合計の record 上限」として扱う。
 * - 全体保持件数 totalRecords が bufferSize に達したら、leaveRate% まで全体を削る。
 * - 削除時は保存数の少ない buffer を優先して残し、保存数の多い buffer から削る。
 * - 各 buffer 内では seqnum の古い順に削る。
 */
public class ProposedMethodLogger extends AbstractEventLogger implements IEventLogger {

	public enum PrometObjectRecordingStrategy {
		Strong, Weak, Id
	}

	/** 全 buffer 合計で記録する最大イベント数 */
	private int bufferSize;

	/** 全体 trim 後に残す割合。80 なら全体の 80% を残す */
	private int leaveRate;

	/** 各 dataId に対応する buffer のリスト */
	private ArrayList<ProposedMethodBuffer> buffers;

	/** 全 buffer 合計で現在保持しているイベント数 */
	private int totalRecords;

	/**
	 * global trim で決まった共通上限 k を、trim 後も各 buffer の保持上限として
	 * 効かせ続けるかどうか（promet の keepk オプション）。
	 *
	 * <p>論文 3.1 節の「既に fi が k に到達しているイベントに対しては、新たなイベントの
	 * 発生ごとに最も古いイベントのデータが上書きされるので、イベントの合計数が増加しない。
	 * これにより、アルゴリズムの頻繁な実行が起こらないようにしている」に対応する。</p>
	 *
	 * <p>有効にすると global trim の実行回数が大きく減る一方、k が再上昇しないため
	 * 保存率がわずかに下がる。挙動が変わるので既定は false。</p>
	 */
	private final boolean keepK;

	/**
	 * 直近の global trim で決まった共通上限 k。keepK が真のときだけ意味を持つ。
	 * trim 前の初期値は全体容量そのもの（実質的に上限なし）。
	 */
	private int currentCap;

	/** global trim を実際に実行した回数。効果測定とテスト用。 */
	private int trimCount;

	/**
	 * 共通上限 cap が 0 になったとき（1 命令あたり 1 件すら保持できない退化ケース）に、
	 * トレースの保存自体を諦めるかどうか（promet の abortonzerok オプション）。
	 *
	 * <p>論文 3.2 節は、この状況では k = 0 としてエラーを記録し、実行トレースの保存自体を
	 * 諦めるとしている。既定の false では、従来どおり余り枠を保存数の少ない buffer に
	 * 配って保存を継続する（警告は 1 回だけ出す）。</p>
	 */
	private final boolean abortOnZeroCap;

	/** cap が 0 になったことがあるか。JSON の zeroCapReached として出力する。 */
	private boolean zeroCapReached;

	/** cap == 0 の警告を出したか。毎 trim で出さないための抑制フラグ。 */
	private boolean zeroCapWarned;

	/** abortOnZeroCap により値の保存を停止したか。停止後は freq だけを数える。 */
	private boolean tracingAborted;

	/** 出力先トレースファイル */
	private File traceFile;

	/** 文字列の全内容を記録するかどうかのフラグ */
	@SuppressWarnings("unused")
	private boolean recordString;

	/** オブジェクトの記録保持戦略 */
	private PrometObjectRecordingStrategy keepObject;

	/** JSON形式で出力するかどうかのフラグ */
	private boolean outputJson;

	/** エラーメッセージを記録するためのロガー */
	private IErrorLogger logger;

	private boolean closed;
	private ObjectIdMap objectIDs;
	private int saveCount;
	private static AtomicLong seqnum = new AtomicLong(0);

	private static class TrimPlan {
		private int dataId;
		private ProposedMethodBuffer buffer;
		private int originalSize;
		private int keepSize;

		private TrimPlan(int dataId, ProposedMethodBuffer buffer, int originalSize, int keepSize) {
			this.dataId = dataId;
			this.buffer = buffer;
			this.originalSize = originalSize;
			this.keepSize = keepSize;
		}
	}

	/**
	 * trim 計画の並び順。originalSize 昇順、同値なら dataId 昇順。
	 *
	 * この順序は 2 つの用途で共有される。
	 * 1. 累積和を作るための昇順ソート（cap の二分探索を O(1) 評価にするため）
	 * 2. 余り枠を「保存数が少ない buffer から」+1 ずつ配る順序
	 * 同じ順序なのでソートは trim 1 回につき 1 度で済む。
	 */
	private static final Comparator<TrimPlan> TRIM_PLAN_ORDER = new Comparator<TrimPlan>() {
		@Override
		public int compare(TrimPlan a, TrimPlan b) {
			int bySize = Integer.compare(a.originalSize, b.originalSize);
			if (bySize != 0) return bySize;
			return Integer.compare(a.dataId, b.dataId);
		}
	};

	/**
	 * ロガーのインスタンスを作成します。keepK / abortOnZeroCap はいずれも無効（既定）。
	 */
	public ProposedMethodLogger(File traceFile, int bufferSize, int leaveRate, boolean recordString,
			PrometObjectRecordingStrategy keepObject, boolean outputJson, IErrorLogger errorLogger) {
		this(traceFile, bufferSize, leaveRate, recordString, keepObject, outputJson, false, false, errorLogger);
	}

	/**
	 * ロガーのインスタンスを作成します。abortOnZeroCap は無効（既定）。
	 *
	 * @param keepK true なら、global trim で決まった共通上限 k を trim 後も
	 *              各 buffer の保持上限として効かせ続ける（promet の keepk オプション）
	 */
	public ProposedMethodLogger(File traceFile, int bufferSize, int leaveRate, boolean recordString,
			PrometObjectRecordingStrategy keepObject, boolean outputJson, boolean keepK, IErrorLogger errorLogger) {
		this(traceFile, bufferSize, leaveRate, recordString, keepObject, outputJson, keepK, false, errorLogger);
	}

	/**
	 * ロガーのインスタンスを作成します。
	 *
	 * @param keepK          true なら、global trim で決まった共通上限 k を trim 後も
	 *                       各 buffer の保持上限として効かせ続ける（promet の keepk オプション）
	 * @param abortOnZeroCap true なら、共通上限が 0 になった時点でトレースの保存を諦め、
	 *                       以降は freq の集計だけを続ける（promet の abortonzerok オプション）
	 */
	public ProposedMethodLogger(File traceFile, int bufferSize, int leaveRate, boolean recordString,
			PrometObjectRecordingStrategy keepObject, boolean outputJson, boolean keepK, boolean abortOnZeroCap,
			IErrorLogger errorLogger) {
		super("promet");
		this.abortOnZeroCap = abortOnZeroCap;
		this.traceFile = traceFile;
		this.bufferSize = Math.max(1, bufferSize);
		this.leaveRate = (leaveRate < 1 || leaveRate > 99) ? 80 : leaveRate;
		this.recordString = recordString;
		this.buffers = new ArrayList<>();
		this.totalRecords = 0;
		this.keepObject = keepObject;
		this.outputJson = outputJson;
		this.keepK = keepK;
		this.currentCap = this.bufferSize;
		this.logger = errorLogger;

		if (this.keepObject == PrometObjectRecordingStrategy.Id) {
			objectIDs = new ObjectIdMap(65536);
		}
	}

	/**
	 * 指定された dataId に対応する buffer を取得する。
	 * ここでは trim しない。promet では全体容量を ProposedMethodLogger 側で管理する。
	 */
	private ProposedMethodBuffer getBuffer(int dataId, Class<?> type) {
		while (dataId >= buffers.size()) {
			buffers.add(null);
		}
		ProposedMethodBuffer buf = buffers.get(dataId);
		if (buf == null) {
			// keepK が偽なら、bufferSize は全体容量だが単一 buffer が理論上保持し得る
			// 最大値も全体容量なので、保持上限として同じ値を渡す。
			//
			// keepK が真なら、trim 後に初めて現れた dataId も共通の k で制限する。
			// 「すべてのイベントの件数が高々 k 件」という論文の意味論に合わせるため。
			buf = new ProposedMethodBuffer(type, keepK ? currentCap : bufferSize, keepObject);
			buffers.set(dataId, buf);
		}
		return buf;
	}

	/**
	 * 追加前に全体容量を確認し、満杯なら全体 trim を実行する。
	 * 追加前に trim することで、totalRecords が bufferSize を超えた状態を作らない。
	 */
	private void ensureGlobalCapacityBeforeRecord() {
		if (totalRecords >= bufferSize) {
			trimAllBuffers();
		}
	}

	/**
	 * 値を記録する前の共通処理。全体容量を確保し、値を保存してよいかを返す。
	 *
	 * <p>abortonzerok による保存停止は、この呼び出しの中の global trim で初めて
	 * 起こることがある。その場合、停止の引き金になったイベント自身も保存してはならないので、
	 * 容量確保のあとにもう一度停止フラグを見る。</p>
	 *
	 * @param dataId 対象のイベント
	 * @param type   buffer を新規作成する場合の要素型
	 * @return 値を保存してよいなら true。保存停止中なら freq だけ数えて false
	 */
	private boolean ensureCapacityOrCountOnly(int dataId, Class<?> type) {
		if (!tracingAborted) {
			ensureGlobalCapacityBeforeRecord();
		}
		if (tracingAborted) {
			countEventOnly(dataId, type);
			return false;
		}
		return true;
	}

	/**
	 * イベントを1件追加した後に全体保持件数を更新する。
	 *
	 * <p>追加によって buffer の保持件数が実際に何件増えたかを見る。
	 * keepK が真で、その buffer が既に保持上限 k に達している場合は、追加しても
	 * 最古のイベントが上書きされるだけで保持件数は増えない。この「合計数が増加しない」
	 * 状態こそが global trim の実行頻度を下げる仕組みなので、無条件に +1 してはならない。</p>
	 *
	 * <p>keepK が偽のとき、各 buffer の保持上限は全体容量 bufferSize と等しく、
	 * 追加直前には ensureGlobalCapacityBeforeRecord によって
	 * storedSize &lt;= totalRecords &lt; bufferSize が保証されている。
	 * したがって delta は常に 1 であり、従来の無条件 +1 と完全に一致する。</p>
	 *
	 * @param sizeBefore 追加前の buffer の保持件数
	 * @param buffer     追加先の buffer
	 */
	private void onRecordAdded(ProposedMethodBuffer buffer, int sizeBefore) {
		totalRecords += buffer.size() - sizeBefore;
	}

	/**
	 * @return trim 後に全体で残すイベント数
	 */
	private int getTargetTotalRecords() {
		int target = (int)(((long)bufferSize * (long)leaveRate) / 100L);

		// 追加前 trim のため、target が bufferSize と同じだと空きが作れない。
		if (target >= bufferSize) {
			target = bufferSize - 1;
		}
		if (target < 0) {
			target = 0;
		}
		return target;
	}

	/**
	 * 昇順ソート済みの保持件数と、その累積和から、
	 * sum(min(size_i, cap)) &lt;= targetTotal を満たす最大の cap を二分探索で求める。
	 *
	 * <p>二分探索の各ステップは、buffer 全体を走査せずに
	 * {@link #cappedTotal(int[], long[], int)} で評価される。
	 * 探索そのものの構造（条件を満たす最大値を求める
	 * {@code while (low < high)} / {@code mid = (low + high + 1) / 2}）は
	 * buffer 走査版と完全に同一なので、返す cap も常に一致する。</p>
	 *
	 * @param sortedSizes 各 buffer の保持件数を昇順に並べたもの
	 * @param prefixSums  sortedSizes の累積和。prefixSums[j] は先頭 j 件の合計。長さは n+1
	 * @param targetTotal trim 後に全体で残すイベント数
	 * @return 条件を満たす最大の cap
	 */
	static int findGlobalCap(int[] sortedSizes, long[] prefixSums, int targetTotal) {
		int n = sortedSizes.length;
		int maxSize = (n == 0) ? 0 : sortedSizes[n - 1];

		int low = 0;
		int high = maxSize;
		while (low < high) {
			int mid = (low + high + 1) / 2;
			long cappedTotal = cappedTotal(sortedSizes, prefixSums, mid);

			if (cappedTotal <= targetTotal) {
				low = mid;
			} else {
				high = mid - 1;
			}
		}
		return low;
	}

	/**
	 * cap を各 buffer の保持上限とした場合の全体保持件数 sum(min(size_i, cap)) を求める。
	 *
	 * <p>sortedSizes は昇順なので、cap 以下の要素は必ず先頭側に固まっている。
	 * その個数を j とすると、先頭 j 件はそのまま残り（累積和 prefixSums[j]）、
	 * 残る n-j 件はすべて cap に切り詰められる。</p>
	 */
	private static long cappedTotal(int[] sortedSizes, long[] prefixSums, int cap) {
		int j = upperBound(sortedSizes, cap);
		return prefixSums[j] + (long)(sortedSizes.length - j) * cap;
	}

	/**
	 * 昇順ソート済み配列で、value 以下の要素数を返す。
	 */
	private static int upperBound(int[] sortedSizes, int value) {
		int low = 0;
		int high = sortedSizes.length;
		while (low < high) {
			int mid = (low + high) >>> 1;
			if (sortedSizes[mid] <= value) {
				low = mid + 1;
			} else {
				high = mid;
			}
		}
		return low;
	}

	/**
	 * cap == 0 になったことを 1 回だけ警告する。
	 *
	 * <p>この状態は毎回の global trim で繰り返し起こるので、
	 * フラグで抑制して 2 回目以降は何も出さない。</p>
	 *
	 * @param eventKinds 現在保持している buffer の数（= 記録対象になったイベント種類数 n）
	 */
	private void warnZeroCapOnce(int eventKinds) {
		if (zeroCapWarned) return;
		zeroCapWarned = true;
		if (logger == null) return;

		logger.log("promet: the common per-event cap k became 0"
				+ " (event kinds n=" + eventKinds + ", size L=" + bufferSize + ", leaverate=" + leaveRate + ")."
				+ " There are too many event kinds to keep even one record each."
				+ " Recording continues: the remaining budget is distributed to the least-recorded events."
				+ " Specify abortonzerok=true to give up recording the trace instead."
				+ " This warning is reported only once.");
	}

	/**
	 * トレースの保存を停止する。以降 recordEvent は freq だけを更新する。
	 *
	 * <p>論文 3.2 節の「実装上は k = 0 としてエラーを記録し、実行トレースの保存自体を
	 * 諦める」に対応する。既存 buffer は保持件数を捨てて配列を解放するが、
	 * freq を出力できるように count は残す。</p>
	 *
	 * @param eventKinds 現在保持している buffer の数（= 記録対象になったイベント種類数 n）
	 */
	private void abortTracing(int eventKinds) {
		if (tracingAborted) return;
		tracingAborted = true;

		for (ProposedMethodBuffer b: buffers) {
			if (b != null) b.releaseStorage();
		}
		totalRecords = 0;

		if (logger != null) {
			logger.log("promet: the common per-event cap k became 0"
					+ " (event kinds n=" + eventKinds + ", size L=" + bufferSize + ", leaverate=" + leaveRate + ")."
					+ " There are too many event kinds to keep even one record each."
					+ " abortonzerok=true, so recording the trace is given up here."
					+ " Only event frequencies (freq) are collected from now on.");
		}
	}

	/**
	 * 保存停止後に、値を保存せず freq だけを数える。
	 *
	 * @param dataId 対象のイベント
	 * @param type   buffer を新規作成する場合の要素型。値は保存しないので出力には現れない
	 */
	private void countEventOnly(int dataId, Class<?> type) {
		while (dataId >= buffers.size()) {
			buffers.add(null);
		}
		ProposedMethodBuffer buf = buffers.get(dataId);
		if (buf == null) {
			// 保存はしないので、配列は最小サイズで確保する。
			buf = new ProposedMethodBuffer(type, 1, keepObject);
			buf.releaseStorage();
			buffers.set(dataId, buf);
		}
		buf.countOnly();
	}

	/**
	 * 全 buffer 合計が上限に達したときに実行する global trim。
	 *
	 * 1. 非 null な buffer を1回走査して trim 計画と全体保持件数を作る。
	 * 2. originalSize 昇順にソートし、累積和を作る。
	 * 3. 二分探索で、各 buffer の共通保持上限 cap を決める（各ステップ O(1) 評価）。
	 * 4. 小さい buffer は残し、大きい buffer を cap 付近まで削る。
	 * 5. 余り枠を、保存数の少ない buffer から +1 ずつ配る。
	 * 6. 各 buffer 内では seqnum の古い順に削る。
	 */
	private void trimAllBuffers() {
		// 手順1: buffer の走査はここ1回だけ。
		// 全体保持件数の再計算（旧 recomputeTotalRecords）も同じ走査に統合している。
		ArrayList<TrimPlan> plans = new ArrayList<>();
		long total = 0;
		for (int i=0; i<buffers.size(); i++) {
			ProposedMethodBuffer b = buffers.get(i);
			if (b == null) continue;

			int originalSize = b.size();
			plans.add(new TrimPlan(i, b, originalSize, 0));
			total += originalSize;
		}
		totalRecords = (total > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int)total;

		int targetTotal = getTargetTotalRecords();
		if (totalRecords <= targetTotal) {
			return;
		}

		// 手順2: originalSize 昇順（同値なら dataId 昇順）。
		// このソート結果を cap の二分探索と余り枠の配分の両方で使い回す。
		Collections.sort(plans, TRIM_PLAN_ORDER);

		// 手順3: 累積和 P[0..n]。
		int n = plans.size();
		int[] sizes = new int[n];
		long[] prefixSums = new long[n + 1];
		for (int i=0; i<n; i++) {
			sizes[i] = plans.get(i).originalSize;
			prefixSums[i + 1] = prefixSums[i] + sizes[i];
		}

		// 手順4: cap の二分探索。
		int cap = findGlobalCap(sizes, prefixSums, targetTotal);

		// 手順4b: cap == 0 は、イベント種類が多すぎて 1 種類あたり 1 件すら
		// 保持できない退化ケース（論文 3.2 節）。
		if (cap == 0) {
			zeroCapReached = true;
			if (abortOnZeroCap) {
				abortTracing(n);
				return;
			}
			warnZeroCapOnce(n);
		}

		// 手順5: 各 buffer の保持件数。
		long plannedTotal = 0;
		for (TrimPlan p: plans) {
			p.keepSize = Math.min(p.originalSize, cap);
			plannedTotal += p.keepSize;
		}

		// 手順6: cap だけだと targetTotal より少なくなる場合がある。
		// 余った枠は、保存数が少ない buffer を優先して配る。
		// これにより「保存の少ないものは残す / 保存の多いところから削る」方針に寄せる。
		long remainingBudget = targetTotal - plannedTotal;
		for (TrimPlan p: plans) {
			if (remainingBudget <= 0) break;

			if (p.originalSize > p.keepSize) {
				p.keepSize++;
				remainingBudget--;
			}
		}

		// 手順7
		int newTotal = 0;
		for (TrimPlan p: plans) {
			p.buffer.trimToSize(p.keepSize);
			newTotal += p.buffer.size();
		}

		totalRecords = newTotal;
		trimCount++;

		// 手順8: keepK が真なら、共通上限 k を trim 後も各 buffer に効かせ続ける。
		if (keepK) {
			currentCap = cap;
			for (TrimPlan p: plans) {
				// keepSize は余り枠の再配分で cap + 1 になり得る。max を取らないと
				// storedSize > retentionLimit の不整合になるため、ここは必ず max。
				p.buffer.setRetentionLimit(Math.max(cap, p.keepSize));
			}
		}
	}

	// --- IEventLogger の記録メソッド群 ---
	// global trim と totalRecords 更新を一貫させるため synchronized にしている。

	@Override
	public synchronized void recordEvent(int dataId, boolean value) {
		if (!ensureCapacityOrCountOnly(dataId, boolean.class)) return;
		ProposedMethodBuffer buf = getBuffer(dataId, boolean.class);
		int sizeBefore = buf.size();
		buf.addBoolean(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded(buf, sizeBefore);
	}

	@Override
	public synchronized void recordEvent(int dataId, byte value) {
		if (!ensureCapacityOrCountOnly(dataId, byte.class)) return;
		ProposedMethodBuffer buf = getBuffer(dataId, byte.class);
		int sizeBefore = buf.size();
		buf.addByte(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded(buf, sizeBefore);
	}

	@Override
	public synchronized void recordEvent(int dataId, char value) {
		if (!ensureCapacityOrCountOnly(dataId, char.class)) return;
		ProposedMethodBuffer buf = getBuffer(dataId, char.class);
		int sizeBefore = buf.size();
		buf.addChar(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded(buf, sizeBefore);
	}

	@Override
	public synchronized void recordEvent(int dataId, double value) {
		if (!ensureCapacityOrCountOnly(dataId, double.class)) return;
		ProposedMethodBuffer buf = getBuffer(dataId, double.class);
		int sizeBefore = buf.size();
		buf.addDouble(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded(buf, sizeBefore);
	}

	@Override
	public synchronized void recordEvent(int dataId, float value) {
		if (!ensureCapacityOrCountOnly(dataId, float.class)) return;
		ProposedMethodBuffer buf = getBuffer(dataId, float.class);
		int sizeBefore = buf.size();
		buf.addFloat(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded(buf, sizeBefore);
	}

	@Override
	public synchronized void recordEvent(int dataId, int value) {
		if (!ensureCapacityOrCountOnly(dataId, int.class)) return;
		ProposedMethodBuffer buf = getBuffer(dataId, int.class);
		int sizeBefore = buf.size();
		buf.addInt(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded(buf, sizeBefore);
	}

	@Override
	public synchronized void recordEvent(int dataId, long value) {
		if (!ensureCapacityOrCountOnly(dataId, long.class)) return;
		ProposedMethodBuffer buf = getBuffer(dataId, long.class);
		int sizeBefore = buf.size();
		buf.addLong(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded(buf, sizeBefore);
	}

	@Override
	public synchronized void recordEvent(int dataId, short value) {
		if (!ensureCapacityOrCountOnly(dataId, short.class)) return;
		ProposedMethodBuffer buf = getBuffer(dataId, short.class);
		int sizeBefore = buf.size();
		buf.addShort(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded(buf, sizeBefore);
	}

	@Override
	public synchronized void recordEvent(int dataId, Object value) {
		Class<?> type = (keepObject == PrometObjectRecordingStrategy.Id) ? ObjectId.class : Object.class;
		if (!ensureCapacityOrCountOnly(dataId, type)) return;
		if (keepObject == PrometObjectRecordingStrategy.Id) {
			ProposedMethodBuffer buf = getBuffer(dataId, ObjectId.class);
			int sizeBefore = buf.size();
			buf.addObjectId(objectIDs.getObjectId(value), seqnum.getAndIncrement(), ThreadId.get());
			onRecordAdded(buf, sizeBefore);
		} else {
			ProposedMethodBuffer buf = getBuffer(dataId, Object.class);
			int sizeBefore = buf.size();
			buf.addObject(value, seqnum.getAndIncrement(), ThreadId.get());
			onRecordAdded(buf, sizeBefore);
		}
	}

	// --- AbstractEventLogger のオーバーライド ---

	@Override
	protected boolean isRecorded(int dataid) {
		// record が 0 まで削られた dataId も、freq を出力できるように count() > 0 で判定する。
		return dataid < buffers.size() && buffers.get(dataid) != null && buffers.get(dataid).count() > 0;
	}

	@Override
	protected String getColumnNames() {
		StringBuilder buf = new StringBuilder("freq,record");
		for (int i = 1; i <= bufferSize; i++) {
			buf.append(",value").append(i).append(",seqnum").append(i).append(",thread").append(i);
		}
		return buf.toString();
	}

	@Override
	protected void writeAttributes(StringBuilder builder, DataInfo d) {
		ProposedMethodBuffer buf = buffers.get(d.getDataId());
		// カラム数は getColumnNames のヘッダと揃える必要がある。keepK 有効時は
		// buffer ごとに保持上限が異なるので、全体容量 bufferSize を明示的に渡す。
		if (buf != null) builder.append(buf.toCsvString(bufferSize));
	}

	/**
	 * 共通上限が 0 になったことがある場合だけ、トップレベルに zeroCapReached を足す。
	 *
	 * <p>既存キーは変更せず追加のみ。0 にならない通常の実行では何も書かないので、
	 * 出力は本オプション導入前と一致する。</p>
	 */
	@Override
	protected void writeTopLevelFields(PrintWriter w) {
		if (zeroCapReached) {
			w.write(", \"zeroCapReached\":true");
		}
	}

	/**
	 * @return global trim を実際に実行した回数。効果測定とテスト用
	 */
	public synchronized int getTrimCount() {
		return trimCount;
	}

	/**
	 * @return 共通上限が 0 になったことがあるか
	 */
	public synchronized boolean isZeroCapReached() {
		return zeroCapReached;
	}

	/**
	 * @return abortonzerok によりトレースの保存を停止したか
	 */
	public synchronized boolean isTracingAborted() {
		return tracingAborted;
	}

	/**
	 * @return 直近の global trim で決まった共通上限 k。keepK が偽なら常に全体容量
	 */
	public synchronized int getCurrentCap() {
		return currentCap;
	}

	@Override
	protected void writeAttributes(JsonBuffer json, DataInfo d) {
		ProposedMethodBuffer buf = buffers.get(d.getDataId());
		if (buf != null) buf.writeJson(json, false);
	}

	@Override
	public synchronized void save(boolean resetTrace) {
		saveCount++;
		File f = new File(traceFile.getAbsolutePath() + "." + Integer.toString(saveCount) + (outputJson ? ".json" : ".txt"));
		try (PrintWriter w = new PrintWriter(new FileWriter(f))) {
			if (outputJson) saveJson(w); else saveText(w);
		} catch (Exception e) {
			if (logger != null) logger.log(e);
		}

		if (resetTrace) {
			buffers = new ArrayList<>();
			totalRecords = 0;
			// 共通上限 k もトレースの状態なので、区間をリセットしたら上限なしに戻す。
			// そうしないと、空になった直後の区間まで前の区間の k で制限され続ける。
			// trimCount は測定用の累積カウンタなのでリセットしない。
			currentCap = bufferSize;
		}
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		if (objectIDs != null) objectIDs.close();

		try (PrintWriter w = new PrintWriter(new FileWriter(traceFile))) {
			if (outputJson) saveJson(w); else saveText(w);
		} catch (Exception e) {
			if (logger != null) logger.log(e);
		}
	}
}
