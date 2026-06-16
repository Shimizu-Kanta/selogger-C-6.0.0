package selogger.logging.io;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.Arrays;
import selogger.logging.io.ProposedMethodLogger.PrometObjectRecordingStrategy;
import selogger.logging.util.JsonBuffer;
import selogger.logging.util.ObjectId;

/**
 * データIDの最新k個のイベントを記録するリングバッファ。
 * 物理シフトによる低水位トリムをサポートします。
 */
public class ProposedMethodBuffer {

	private static final int DEFAULT_CAPACITY = 32;

	/** ユーザーが指定した最大保持数 */
	private int bufferSize;
	
	/** 現在の配列の物理サイズ */
	private int capacity;
	
	/** 次に書き込む物理インデックス */
	private int nextPos = 0;
	
	/** この場所で発生した全イベントの累積数 */
	private long count = 0;

	/** 現在保持しているイベント数 **/
	private int storedSize = 0;
	
	/** 値を格納する配列（int[], Object[] 等） */
	private Object array;
	
	/** シーケンス番号の配列 */
	private long[] seqnums;
	
	/** スレッドIDの配列 */
	private int[] threads;
	
	/** オブジェクトの記録保持戦略 */
	private PrometObjectRecordingStrategy keepObject;

	/**
	 * バッファを作成します。
	 */
	public ProposedMethodBuffer(Class<?> type, int bufferSize, PrometObjectRecordingStrategy keepObject) {
		this.capacity = Math.min(DEFAULT_CAPACITY, bufferSize);
		this.bufferSize = bufferSize;
		this.array = Array.newInstance(type, capacity);
		this.seqnums = new long[capacity];
		this.threads = new int[capacity];
		this.keepObject = keepObject;
	}

	/**
	 * 書き込み位置を更新し、必要に応じて物理配列を拡張します。
	 * @return 書き込み先の物理インデックス
	 */
	private int getNextIndex() {
    	count++;

		// bufferSize に達するまでは必要に応じて物理配列を拡張
		if (storedSize >= capacity && capacity < bufferSize) {
			capacity = Math.min(capacity * 2, bufferSize);
			this.seqnums = Arrays.copyOf(this.seqnums, capacity);
			this.threads = Arrays.copyOf(this.threads, capacity);
			expandValueArray(capacity);
		}

		int next = nextPos;

		if (storedSize < bufferSize) {
			storedSize++;
		}

		nextPos++;
		if (nextPos >= bufferSize) {
			nextPos = 0;
		}

		return next;
	}

	/**
	 * 格納されている型に応じて値を保持する配列を拡張します。
	 */
	private void expandValueArray(int newCapacity) {
		if (array instanceof int[]) array = Arrays.copyOf((int[])array, newCapacity);
		else if (array instanceof long[]) array = Arrays.copyOf((long[])array, newCapacity);
		else if (array instanceof float[]) array = Arrays.copyOf((float[])array, newCapacity);
		else if (array instanceof double[]) array = Arrays.copyOf((double[])array, newCapacity);
		else if (array instanceof char[]) array = Arrays.copyOf((char[])array, newCapacity);
		else if (array instanceof short[]) array = Arrays.copyOf((short[])array, newCapacity);
		else if (array instanceof byte[]) array = Arrays.copyOf((byte[])array, newCapacity);
		else if (array instanceof boolean[]) array = Arrays.copyOf((boolean[])array, newCapacity);
		else array = Arrays.copyOf((Object[])array, newCapacity);
	}

	// --- 各型ごとの追加メソッド ---

	public synchronized void addBoolean(boolean value, long seqnum, int threadId) {
		int index = getNextIndex();
		((boolean[])array)[index] = value;
		seqnums[index] = seqnum;
		threads[index] = threadId;
	}

	public synchronized void addByte(byte value, long seqnum, int threadId) {
		int index = getNextIndex();
		((byte[])array)[index] = value;
		seqnums[index] = seqnum;
		threads[index] = threadId;
	}

	public synchronized void addChar(char value, long seqnum, int threadId) {
		int index = getNextIndex();
		((char[])array)[index] = value;
		seqnums[index] = seqnum;
		threads[index] = threadId;
	}

	public synchronized void addShort(short value, long seqnum, int threadId) {
		int index = getNextIndex();
		((short[])array)[index] = value;
		seqnums[index] = seqnum;
		threads[index] = threadId;
	}

	public synchronized void addInt(int value, long seqnum, int threadId) {
		int index = getNextIndex();
		((int[])array)[index] = value;
		seqnums[index] = seqnum;
		threads[index] = threadId;
	}

	public synchronized void addLong(long value, long seqnum, int threadId) {
		int index = getNextIndex();
		((long[])array)[index] = value;
		seqnums[index] = seqnum;
		threads[index] = threadId;
	}

	public synchronized void addFloat(float value, long seqnum, int threadId) {
		int index = getNextIndex();
		((float[])array)[index] = value;
		seqnums[index] = seqnum;
		threads[index] = threadId;
	}

	public synchronized void addDouble(double value, long seqnum, int threadId) {
		int index = getNextIndex();
		((double[])array)[index] = value;
		seqnums[index] = seqnum;
		threads[index] = threadId;
	}

	public synchronized void addObject(Object value, long seqnum, int threadId) {
		int index = getNextIndex();
		if (keepObject == PrometObjectRecordingStrategy.Strong) {
			((Object[])array)[index] = value;
		} else {
			((Object[])array)[index] = (value != null) ? new WeakReference<>(value) : null;
		}
		seqnums[index] = seqnum;
		threads[index] = threadId;
	}

	public synchronized void addObjectId(ObjectId value, long seqnum, int threadId) {
		int index = getNextIndex();
		((ObjectId[])array)[index] = value;
		seqnums[index] = seqnum;
		threads[index] = threadId;
	}

	/**
	 * 物理シフトによるお片付け。
	 * @param trimCount 削除する（前に詰める）イベント数
	 */
	public synchronized void trimOldEvents(int trimCount) {
		if (trimCount <= 0 || storedSize == 0) return;

		int actualTrim = Math.min(trimCount, storedSize);
		int newSize = storedSize - actualTrim;

		Object newArray = Array.newInstance(array.getClass().getComponentType(), capacity);
		long[] newSeqnums = new long[capacity];
		int[] newThreads = new int[capacity];

		for (int i = 0; i < newSize; i++) {
			int oldIdx = getPos(actualTrim + i);
			Array.set(newArray, i, Array.get(array, oldIdx));
			newSeqnums[i] = seqnums[oldIdx];
			newThreads[i] = threads[oldIdx];
		}

		array = newArray;
		seqnums = newSeqnums;
		threads = newThreads;

		storedSize = newSize;
		nextPos = newSize;

		// count は累積発生回数なので変更しない
	}

	/**
	 * @return このバッファに論理的に保持されているイベント数
	 */
	public synchronized int size() {
		return storedSize;
	}

	/**
	 * @return 累積発生回数
	 */
	public synchronized long count() {
		return count;
	}

	/**
	 * 論理的なi番目（最古=0）の物理インデックスを計算
	 */
	private int getPos(int i) {
		if (storedSize < bufferSize ) {
			return i;
		}

		return (nextPos + i) % bufferSize;
	}

	/**
	 * CSV形式での文字列表現を生成します。
	 */
	@Override
	public synchronized String toString() {
		StringBuilder buf = new StringBuilder();
		buf.append(count()).append(",").append(size());
		int currentSize = size();
		for (int i = 0; i < bufferSize; i++) {
			buf.append(",");
			if (i < currentSize) {
				int idx = getPos(i);
				buf.append(getValueString(idx)).append(",");
				buf.append(seqnums[idx]).append(",");
				buf.append(threads[idx]);
			} else {
				// 有効範囲外は空カラムで埋める
				buf.append(",,");
			}
		}
		return buf.toString();
	}

	/**
	 * 配列内の値を文字列に変換します。
	 */
	private String getValueString(int idx) {
		if (array instanceof int[]) return Integer.toString(((int[])array)[idx]);
		if (array instanceof long[]) return Long.toString(((long[])array)[idx]);
		if (array instanceof double[]) return Double.toString(((double[])array)[idx]);
		if (array instanceof float[]) return Float.toString(((float[])array)[idx]);
		if (array instanceof boolean[]) return Boolean.toString(((boolean[])array)[idx]);
		if (array instanceof char[]) return Integer.toString((int)((char[])array)[idx]);
		if (array instanceof short[]) return Short.toString(((short[])array)[idx]);
		if (array instanceof byte[]) return Byte.toString(((byte[])array)[idx]);
		
		Object o = ((Object[])array)[idx];
		if (o instanceof WeakReference) o = ((WeakReference<?>)o).get();
		if (o instanceof ObjectId) return Long.toString(((ObjectId)o).getId());
		return (o != null) ? o.toString() : "null";
	}

	/**
	 * JSON形式でバッファの内容を書き出します。
	 */
	public synchronized void writeJson(JsonBuffer buf, boolean skipValues) {
		int len = size();
		buf.writeNumberField("freq", count());
		buf.writeNumberField("record", size());

		if (!skipValues && len > 0) {
			buf.writeArrayFieldStart("value");
			for (int i = 0; i < len; i++) {
				int idx = getPos(i);
				if (array instanceof int[]) buf.writeNumber(((int[])array)[idx]);
				else if (array instanceof long[]) buf.writeNumber(((long[])array)[idx]);
				else if (array instanceof float[]) buf.writeNumber(((float[])array)[idx]);
				else if (array instanceof double[]) buf.writeNumber(((double[])array)[idx]);
				else if (array instanceof char[]) buf.writeNumber((int)((char[])array)[idx]);
				else if (array instanceof short[]) buf.writeNumber(((short[])array)[idx]);
				else if (array instanceof byte[]) buf.writeNumber(((byte[])array)[idx]);
				else if (array instanceof boolean[]) buf.writeBoolean(((boolean[])array)[idx]);
				else if (array instanceof ObjectId[]) {
					ObjectId id = ((ObjectId[])array)[idx];
					if (id == null) buf.writeNull();
					else {
						buf.writeStartObject();
						buf.writeStringField("id", Long.toString(id.getId()));
						buf.writeStringField("type", id.getClassName());
						if (id.getContent() != null) buf.writeStringField("str", id.getContent());
						buf.writeEndObject();
					}
				} else {
					Object o = ((Object[])array)[idx];
					if (o == null) buf.writeNull();
					else {
						if (keepObject == PrometObjectRecordingStrategy.Weak && o instanceof WeakReference) {
							o = ((WeakReference<?>)o).get();
						}
						buf.writeStartObject();
						if (o == null) buf.writeStringField("id", "<GC>");
						else {
							buf.writeStringField("id", Integer.toHexString(System.identityHashCode(o)));
							buf.writeStringField("type", o.getClass().getName());
							if (o instanceof String) buf.writeEscapedStringField("str", (String)o);
						}
						buf.writeEndObject();
					}
				}
			}
			buf.writeEndArray();

			buf.writeArrayFieldStart("seqnum");
			for (int i = 0; i < len; i++) buf.writeNumber(seqnums[getPos(i)]);
			buf.writeEndArray();

			buf.writeArrayFieldStart("thread");
			for (int i = 0; i < len; i++) buf.writeNumber(threads[getPos(i)]);
			buf.writeEndArray();
		}
	}
}