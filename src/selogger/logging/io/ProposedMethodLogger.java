package selogger.logging.io;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import selogger.logging.IErrorLogger;
import selogger.logging.IEventLogger;
import selogger.logging.util.JsonBuffer;
import selogger.logging.util.ObjectId;
import selogger.logging.util.ObjectIdMap;
import selogger.logging.util.ThreadId;
import selogger.weaver.DataInfo;

/**
 * 物理シフトと水位管理を用いた効率重視のロガー (promet モード)。
 * SELogger 0.6.0 基盤に対応し、全設定変数を保持しています。
 */
public class ProposedMethodLogger extends AbstractEventLogger implements IEventLogger {

	public enum PrometObjectRecordingStrategy {
		Strong, Weak, Id
	}

	/** 各場所で記録する最大イベント数 */
	private int bufferSize;
	
	/** バッファが満杯になった際に残す割合 (1 ~ 99) */
	private int leaveRate;
	
	/** 各データIDに対応するバッファのリスト */
	private ArrayList<ProposedMethodBuffer> buffers;
	
	/** 出力先トレースファイル */
	private File traceFile;
	
	/** 文字列の全内容を記録するかどうかのフラグ */
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

	/**
	 * ロガーのインスタンスを作成します。
	 */
	public ProposedMethodLogger(File traceFile, int bufferSize, int leaveRate, boolean recordString, PrometObjectRecordingStrategy keepObject, boolean outputJson, IErrorLogger errorLogger) {
		super("promet");
		this.traceFile = traceFile;
		this.bufferSize = bufferSize;
		this.leaveRate =  (leaveRate < 1 || leaveRate > 99) ? 80 : leaveRate;
		this.recordString = recordString;
		this.buffers = new ArrayList<>();
		this.keepObject = keepObject;
		this.outputJson = outputJson;
		this.logger = errorLogger;
		if (this.keepObject == PrometObjectRecordingStrategy.Id) {
			objectIDs = new ObjectIdMap(65536);
		}

		if (logger != null) {
			logger.log("ProposedMethodLogger DEBUG: bufferSize=" + this.bufferSize
					+ ", leaveRate=" + this.leaveRate
					+ ", recordString=" + this.recordString
					+ ", keepObject=" + this.keepObject
					+ ", outputJson=" + this.outputJson
					+ ", traceFile=" + this.traceFile.getAbsolutePath()
					+ ", bufferClass=" + ProposedMethodBuffer.class.getProtectionDomain().getCodeSource()
				);
		}
	}

	/**
	 * 指定された dataId に対応するバッファを取得し、必要に応じてトリム（水位管理）を行います。
	 */
	private synchronized ProposedMethodBuffer getBuffer(int dataId, Class<?> type) {
		while (dataId >= buffers.size()) {
			buffers.add(null);
		}
		ProposedMethodBuffer buf = buffers.get(dataId);
		if (buf == null) {
			buf = new ProposedMethodBuffer(type, bufferSize, keepObject);
			buffers.set(dataId, buf);
		}
		
		// 水位管理ロジック: いっぱいになったら一括削除して空きを作る
		if (buf.size() >= bufferSize) {
			int keepItems = bufferSize * leaveRate / 100;
			int itemsToRemove = Math.max(1, bufferSize - keepItems);

			if (logger != null) {
				logger.log("ProposedMethodLogger DEBUG trim before: dataId=" + dataId
						+ ", beforeSize=" + buf.size()
						+ ", beforeCount=" + buf.count()
						+ ", bufferSize=" + bufferSize
						+ ", keepItems=" + keepItems
						+ ", itemsToRemove=" + itemsToRemove);
			}	

			buf.trimOldEvents(itemsToRemove);

			if (logger != null) {
				logger.log("ProposedMethodLogger DEBUG trim after: dataId=" + dataId
						+ ", afterSize=" + buf.size()
						+ ", afterCount=" + buf.count());
			}

		}
		
		return buf;
	}

	// --- IEventLogger の記録メソッド群 ---

	@Override
	public void recordEvent(int dataId, boolean value) {
		getBuffer(dataId, boolean.class).addBoolean(value, seqnum.getAndIncrement(), ThreadId.get());
	}

	@Override
	public void recordEvent(int dataId, byte value) {
		getBuffer(dataId, byte.class).addByte(value, seqnum.getAndIncrement(), ThreadId.get());
	}

	@Override
	public void recordEvent(int dataId, char value) {
		getBuffer(dataId, char.class).addChar(value, seqnum.getAndIncrement(), ThreadId.get());
	}

	@Override
	public void recordEvent(int dataId, double value) {
		getBuffer(dataId, double.class).addDouble(value, seqnum.getAndIncrement(), ThreadId.get());
	}

	@Override
	public void recordEvent(int dataId, float value) {
		getBuffer(dataId, float.class).addFloat(value, seqnum.getAndIncrement(), ThreadId.get());
	}

	@Override
	public void recordEvent(int dataId, int value) {
		getBuffer(dataId, int.class).addInt(value, seqnum.getAndIncrement(), ThreadId.get());
	}

	@Override
	public void recordEvent(int dataId, long value) {
		getBuffer(dataId, long.class).addLong(value, seqnum.getAndIncrement(), ThreadId.get());
	}

	@Override
	public void recordEvent(int dataId, short value) {
		getBuffer(dataId, short.class).addShort(value, seqnum.getAndIncrement(), ThreadId.get());
	}

	@Override
	public void recordEvent(int dataId, Object value) {
		if (keepObject == PrometObjectRecordingStrategy.Id) {
			// Idモードの場合は ObjectId として記録
			getBuffer(dataId, ObjectId.class).addObjectId(objectIDs.getObjectId(value), seqnum.getAndIncrement(), ThreadId.get());
		} else {
			getBuffer(dataId, Object.class).addObject(value, seqnum.getAndIncrement(), ThreadId.get());
		}
	}

	// --- AbstractEventLogger のオーバーライド ---

	@Override
	protected boolean isRecorded(int dataid) {
		return dataid < buffers.size() && buffers.get(dataid) != null && buffers.get(dataid).size() > 0;
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
		} catch (Exception e) { if (logger != null) logger.log(e); }
		if (resetTrace) buffers = new ArrayList<>();
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		if (objectIDs != null) objectIDs.close();
		try (PrintWriter w = new PrintWriter(new FileWriter(traceFile))) {
			if (outputJson) saveJson(w); else saveText(w);
		} catch (Exception e) { if (logger != null) logger.log(e); }
	}
}