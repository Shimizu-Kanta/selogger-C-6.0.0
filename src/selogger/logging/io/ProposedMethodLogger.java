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
	 * ロガーのインスタンスを作成します。
	 */
	public ProposedMethodLogger(File traceFile, int bufferSize, int leaveRate, boolean recordString,
			PrometObjectRecordingStrategy keepObject, boolean outputJson, IErrorLogger errorLogger) {
		super("promet");
		this.traceFile = traceFile;
		this.bufferSize = Math.max(1, bufferSize);
		this.leaveRate = (leaveRate < 1 || leaveRate > 99) ? 80 : leaveRate;
		this.recordString = recordString;
		this.buffers = new ArrayList<>();
		this.totalRecords = 0;
		this.keepObject = keepObject;
		this.outputJson = outputJson;
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
			// bufferSize は全体容量だが、単一 buffer が理論上保持し得る最大値も全体容量なので、
			// ProposedMethodBuffer の物理上限として同じ値を渡す。
			buf = new ProposedMethodBuffer(type, bufferSize, keepObject);
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
	 * イベントを1件追加した後に全体保持件数を更新する。
	 */
	private void onRecordAdded() {
		totalRecords++;
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
	 * 実際の buffer サイズから全体保持件数を再計算する。
	 */
	private int recomputeTotalRecords() {
		long total = 0;
		for (ProposedMethodBuffer b: buffers) {
			if (b != null) {
				total += b.size();
				if (total > Integer.MAX_VALUE) {
					return Integer.MAX_VALUE;
				}
			}
		}
		return (int)total;
	}

	/**
	 * cap を各 buffer の保持上限とした場合の全体保持件数を計算する。
	 */
	private long calculateCappedTotal(int cap) {
		long total = 0;
		for (ProposedMethodBuffer b: buffers) {
			if (b != null) {
				total += Math.min(b.size(), cap);
			}
		}
		return total;
	}

	/**
	 * sum(min(buffer.size(), cap)) <= targetTotal を満たす最大の cap を二分探索で探す。
	 */
	private int findGlobalCap(int targetTotal) {
		int maxSize = 0;
		for (ProposedMethodBuffer b: buffers) {
			if (b != null) {
				maxSize = Math.max(maxSize, b.size());
			}
		}

		int low = 0;
		int high = maxSize;
		while (low < high) {
			int mid = (low + high + 1) / 2;
			long cappedTotal = calculateCappedTotal(mid);

			if (cappedTotal <= targetTotal) {
				low = mid;
			} else {
				high = mid - 1;
			}
		}
		return low;
	}

	/**
	 * 全 buffer 合計が上限に達したときに実行する global trim。
	 *
	 * 1. trim 後に残す全体件数 targetTotal を計算する。
	 * 2. 二分探索で、各 buffer の共通保持上限 cap を決める。
	 * 3. 小さい buffer は残し、大きい buffer を cap 付近まで削る。
	 * 4. 各 buffer 内では seqnum の古い順に削る。
	 */
	private void trimAllBuffers() {
		totalRecords = recomputeTotalRecords();

		int targetTotal = getTargetTotalRecords();
		if (totalRecords <= targetTotal) {
			return;
		}

		int cap = findGlobalCap(targetTotal);
		ArrayList<TrimPlan> plans = new ArrayList<>();
		int plannedTotal = 0;

		for (int i=0; i<buffers.size(); i++) {
			ProposedMethodBuffer b = buffers.get(i);
			if (b == null) continue;

			int originalSize = b.size();
			int keepSize = Math.min(originalSize, cap);
			plans.add(new TrimPlan(i, b, originalSize, keepSize));
			plannedTotal += keepSize;
		}

		// cap だけだと targetTotal より少なくなる場合がある。
		// 余った枠は、保存数が少ない buffer を優先して配る。
		// これにより「保存の少ないものは残す / 保存の多いところから削る」方針に寄せる。
		int remainingBudget = targetTotal - plannedTotal;
		Collections.sort(plans, new Comparator<TrimPlan>() {
			@Override
			public int compare(TrimPlan a, TrimPlan b) {
				int bySize = Integer.compare(a.originalSize, b.originalSize);
				if (bySize != 0) return bySize;
				return Integer.compare(a.dataId, b.dataId);
			}
		});

		for (TrimPlan p: plans) {
			if (remainingBudget <= 0) break;

			if (p.originalSize > p.keepSize) {
				p.keepSize++;
				remainingBudget--;
			}
		}

		int newTotal = 0;
		for (TrimPlan p: plans) {
			p.buffer.trimToSize(p.keepSize);
			newTotal += p.buffer.size();
		}

		totalRecords = newTotal;
	}

	// --- IEventLogger の記録メソッド群 ---
	// global trim と totalRecords 更新を一貫させるため synchronized にしている。

	@Override
	public synchronized void recordEvent(int dataId, boolean value) {
		ensureGlobalCapacityBeforeRecord();
		getBuffer(dataId, boolean.class).addBoolean(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded();
	}

	@Override
	public synchronized void recordEvent(int dataId, byte value) {
		ensureGlobalCapacityBeforeRecord();
		getBuffer(dataId, byte.class).addByte(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded();
	}

	@Override
	public synchronized void recordEvent(int dataId, char value) {
		ensureGlobalCapacityBeforeRecord();
		getBuffer(dataId, char.class).addChar(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded();
	}

	@Override
	public synchronized void recordEvent(int dataId, double value) {
		ensureGlobalCapacityBeforeRecord();
		getBuffer(dataId, double.class).addDouble(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded();
	}

	@Override
	public synchronized void recordEvent(int dataId, float value) {
		ensureGlobalCapacityBeforeRecord();
		getBuffer(dataId, float.class).addFloat(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded();
	}

	@Override
	public synchronized void recordEvent(int dataId, int value) {
		ensureGlobalCapacityBeforeRecord();
		getBuffer(dataId, int.class).addInt(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded();
	}

	@Override
	public synchronized void recordEvent(int dataId, long value) {
		ensureGlobalCapacityBeforeRecord();
		getBuffer(dataId, long.class).addLong(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded();
	}

	@Override
	public synchronized void recordEvent(int dataId, short value) {
		ensureGlobalCapacityBeforeRecord();
		getBuffer(dataId, short.class).addShort(value, seqnum.getAndIncrement(), ThreadId.get());
		onRecordAdded();
	}

	@Override
	public synchronized void recordEvent(int dataId, Object value) {
		ensureGlobalCapacityBeforeRecord();
		if (keepObject == PrometObjectRecordingStrategy.Id) {
			getBuffer(dataId, ObjectId.class).addObjectId(objectIDs.getObjectId(value), seqnum.getAndIncrement(), ThreadId.get());
		} else {
			getBuffer(dataId, Object.class).addObject(value, seqnum.getAndIncrement(), ThreadId.get());
		}
		onRecordAdded();
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
		if (buf != null) builder.append(buf.toString());
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
