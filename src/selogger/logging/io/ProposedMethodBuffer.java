package selogger.logging.io;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.Arrays;

import selogger.logging.io.ProposedMethodLogger.PrometObjectRecordingStrategy;
import selogger.logging.util.JsonBuffer;
import selogger.logging.util.ObjectId;

/**
 * dataId ごとのイベントを保持する buffer。
 *
 * promet では全体容量は ProposedMethodLogger が管理する。
 * この buffer は、指定された keepSize まで自分の中の古いイベントを削る責務を持つ。
 */
public class ProposedMethodBuffer {

	private static final int DEFAULT_CAPACITY = 32;

	/**
	 * この buffer が論理的に保持し得る最大数、かつリングの法（modulus）。
	 *
	 * <p>物理配列サイズ {@link #capacity} とは別物であることに注意。
	 * capacity は必要に応じて retentionLimit まで倍々に伸びる作業用の配列長で、
	 * retentionLimit は「何件まで残すか」という論理的な上限である。</p>
	 *
	 * <p>{@link #setRetentionLimit(int)} で後から下げられる（promet の keepk 有効時に、
	 * global trim で決まった共通上限 k を各 buffer に効かせるために使う）。
	 * 下げても capacity は縮めない。</p>
	 */
	private int retentionLimit;

	/** 現在の配列の物理サイズ。retentionLimit を下げても縮めない。 */
	private int capacity;

	/** 次に書き込む物理インデックス */
	private int nextPos = 0;

	/** この dataId で発生した全イベントの累積数 */
	private long count = 0;

	/** 現在保持しているイベント数 */
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
	public ProposedMethodBuffer(Class<?> type, int retentionLimit, PrometObjectRecordingStrategy keepObject) {
		this.retentionLimit = Math.max(1, retentionLimit);
		this.capacity = Math.min(DEFAULT_CAPACITY, this.retentionLimit);
		this.array = newValueArray(type, capacity);
		this.seqnums = new long[capacity];
		this.threads = new int[capacity];
		this.keepObject = keepObject;
	}

	/**
	 * 要素型 type の配列を size 件ぶん確保する。
	 *
	 * expandValueArray と同じ型分岐スタイルで、promet が実際に使う型
	 * （8種のプリミティブ / ObjectId / Object）をリフレクションなしで確保する。
	 * trim のたびに呼ばれるため、ここでリフレクションを使わないことに意味がある。
	 *
	 * 最後の Array.newInstance だけは残している。コンストラクタが任意の Class&lt;?&gt; を
	 * 受け取る API になっており、上記以外の要素型（テスト等で String.class を渡す場合など）
	 * でも配列の実要素型を保つ必要があるため。ProposedMethodLogger から渡ってくるのは
	 * 上記10種のみなので、この分岐は通常の記録経路では実行されない。
	 */
	private static Object newValueArray(Class<?> type, int size) {
		if (type == int.class) return new int[size];
		if (type == long.class) return new long[size];
		if (type == float.class) return new float[size];
		if (type == double.class) return new double[size];
		if (type == char.class) return new char[size];
		if (type == short.class) return new short[size];
		if (type == byte.class) return new byte[size];
		if (type == boolean.class) return new boolean[size];
		if (type == ObjectId.class) return new ObjectId[size];
		if (type == Object.class) return new Object[size];
		return Array.newInstance(type, size);
	}

	/**
	 * 書き込み位置を更新し、必要に応じて物理配列を拡張します。
	 * @return 書き込み先の物理インデックス
	 */
	private int getNextIndex() {
		count++;

		// retentionLimit に達するまでは必要に応じて物理配列を拡張
		if (storedSize >= capacity && capacity < retentionLimit) {
			capacity = Math.min(capacity * 2, retentionLimit);
			this.seqnums = Arrays.copyOf(this.seqnums, capacity);
			this.threads = Arrays.copyOf(this.threads, capacity);
			expandValueArray(capacity);
		}

		int next = nextPos;

		if (storedSize < retentionLimit) {
			storedSize++;
		}

		nextPos++;
		if (nextPos >= retentionLimit) {
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
	 * この buffer 内の古いイベントを trimCount 件削除する。
	 * 削除順は、この buffer 内における seqnum の古い順。
	 *
	 * @param trimCount 削除するイベント数
	 */
	public synchronized void trimOldEvents(int trimCount) {
		if (trimCount <= 0 || storedSize == 0) return;

		int currentSize = size();
		int actualTrim = Math.min(trimCount, currentSize);
		rebuildLinear(currentSize - actualTrim);
	}

	/**
	 * 新しい側 keepNewest 件だけを、物理インデックス 0..keepNewest-1 に論理順で並べ直す。
	 *
	 * <p>実行後は必ず非折り返し状態（nextPos == storedSize）になるので、
	 * 以降 getPos は恒等写像として振る舞う。</p>
	 *
	 * <p>keepNewest == storedSize を渡すと、削除せずに並べ替えだけを行う。
	 * {@link #setRetentionLimit(int)} がリングの法を変える前に使う。</p>
	 *
	 * @param keepNewest 残す件数（0 &lt;= keepNewest &lt;= storedSize）
	 */
	private void rebuildLinear(int keepNewest) {
		// 削る件数。残すのは論理位置 dropped..storedSize-1。
		int dropped = storedSize - keepNewest;

		// 確保サイズは capacity のまま維持する。keepNewest まで縮めると、その後の再成長で
		// expandValueArray が繰り返し走るため。
		Object newArray = newValueArray(array.getClass().getComponentType(), capacity);
		long[] newSeqnums = new long[capacity];
		int[] newThreads = new int[capacity];

		// リングが折り返している場合があるので、copyRange が最大2区間に分けて
		// System.arraycopy でコピーする。
		copyRange(array, newArray, dropped, keepNewest);
		copyRange(seqnums, newSeqnums, dropped, keepNewest);
		copyRange(threads, newThreads, dropped, keepNewest);

		array = newArray;
		seqnums = newSeqnums;
		threads = newThreads;

		storedSize = keepNewest;
		nextPos = keepNewest;

		// count は累積発生回数なので変更しない
	}

	/**
	 * この buffer の保持上限を newLimit に変更する。
	 *
	 * <p>promet の keepk 有効時に、global trim で決まった共通上限 k を
	 * 各 buffer に効かせ続けるために使う。上限に達した buffer への追記は
	 * 最古のイベントを上書きするだけになり、全体保持件数が増えなくなるので、
	 * global trim の実行頻度が下がる。</p>
	 *
	 * <p>現在の storedSize より小さい値は受け付けない。呼び出し側が先に
	 * {@link #trimToSize(int)} で件数を落としておくこと。storedSize を下回る値が
	 * 渡された場合は storedSize まで引き上げて適用する
	 * （storedSize &gt; retentionLimit という不整合を作らないため）。</p>
	 *
	 * <p>物理配列サイズ {@link #capacity} は縮めない。上限だけを下げる。</p>
	 *
	 * @param newLimit 新しい保持上限。1 未満は 1 に切り上げる
	 */
	public synchronized void setRetentionLimit(int newLimit) {
		newLimit = Math.max(1, newLimit);
		newLimit = Math.max(newLimit, storedSize);

		if (newLimit == retentionLimit) return;

		// getPos / copyRange はリングの法として retentionLimit を使うので、
		// 法を変える前に非折り返し状態へ直しておく必要がある。
		// 折り返しているのは storedSize == retentionLimit のときだけ。
		if (storedSize == retentionLimit) {
			rebuildLinear(storedSize);
		}

		retentionLimit = newLimit;

		// 上限を下げた場合、nextPos が新上限以上になっていることがある。
		if (nextPos >= retentionLimit) {
			nextPos %= retentionLimit;
		}
	}

	/**
	 * @return 現在の保持上限
	 */
	public synchronized int getRetentionLimit() {
		return retentionLimit;
	}

	/**
	 * 値を保存せずに、発生回数だけを 1 増やす。
	 *
	 * <p>promet が abortonzerok によりトレースの保存を諦めたあと、
	 * freq の集計だけを続けるために使う。</p>
	 */
	public synchronized void countOnly() {
		count++;
	}

	/**
	 * 保持している値を捨て、配列を最小サイズまで縮めてメモリを解放する。
	 * 累積発生回数 count は保持する。
	 *
	 * <p>promet が abortonzerok によりトレースの保存を諦めたときに使う。
	 * 以降この buffer に値を追加しないことは呼び出し側の責務
	 * （ProposedMethodLogger は保存停止後 {@link #countOnly()} しか呼ばない）。
	 * 万一追加されても不変条件は壊れず、その 1 件が保持されるだけである。</p>
	 */
	public synchronized void releaseStorage() {
		storedSize = 0;
		nextPos = 0;
		retentionLimit = 1;
		capacity = 1;
		array = newValueArray(array.getClass().getComponentType(), 1);
		seqnums = new long[1];
		threads = new int[1];
	}

	/**
	 * 論理位置 from から len 件を、src の該当区間から dst の先頭へコピーする。
	 *
	 * <p>src はリングとして折り返している可能性があるため、{@link #getPos(int)} の統一形
	 * getPos(i) == (base + i) % retentionLimit にもとづいて最大2区間に分割する。</p>
	 * <ul>
	 * <li>折り返しなし → arraycopy 1回</li>
	 * <li>折り返しあり → 末尾側（physStart..retentionLimit-1）と先頭側（0..）で arraycopy 2回</li>
	 * </ul>
	 *
	 * <p>コピー先 dst は論理順（最古が先頭）に詰め直されるので、呼び出し側は
	 * storedSize / nextPos を len に更新すること。</p>
	 *
	 * <p>src と dst は同じ要素型の配列であること。値配列・seqnums・threads で
	 * 同じ区間計算を共有するために引数型を Object にしている。</p>
	 *
	 * @param src  コピー元のリング配列
	 * @param dst  コピー先の配列（長さは len 以上）
	 * @param from コピーを開始する論理位置（最古 = 0）
	 * @param len  コピーする件数
	 */
	private void copyRange(Object src, Object dst, int from, int len) {
		if (len <= 0) return;

		int physStart = getPos(from);
		int firstLen = Math.min(len, retentionLimit - physStart);

		typedArrayCopy(src, physStart, dst, 0, firstLen);
		if (firstLen < len) {
			// 折り返し分。リングの先頭から残りをコピーする。
			typedArrayCopy(src, 0, dst, firstLen, len - firstLen);
		}
	}

	/**
	 * 要素型ごとに分岐して System.arraycopy を呼ぶ。
	 *
	 * expandValueArray と同じ instanceof による型分岐スタイル。
	 * 静的な要素型が確定するのでボクシングが起こらず、C2 が型ごとの高速な
	 * コピーへインライン展開できる。
	 */
	private static void typedArrayCopy(Object src, int srcPos, Object dst, int dstPos, int len) {
		if (src instanceof int[]) System.arraycopy((int[])src, srcPos, (int[])dst, dstPos, len);
		else if (src instanceof long[]) System.arraycopy((long[])src, srcPos, (long[])dst, dstPos, len);
		else if (src instanceof float[]) System.arraycopy((float[])src, srcPos, (float[])dst, dstPos, len);
		else if (src instanceof double[]) System.arraycopy((double[])src, srcPos, (double[])dst, dstPos, len);
		else if (src instanceof char[]) System.arraycopy((char[])src, srcPos, (char[])dst, dstPos, len);
		else if (src instanceof short[]) System.arraycopy((short[])src, srcPos, (short[])dst, dstPos, len);
		else if (src instanceof byte[]) System.arraycopy((byte[])src, srcPos, (byte[])dst, dstPos, len);
		else if (src instanceof boolean[]) System.arraycopy((boolean[])src, srcPos, (boolean[])dst, dstPos, len);
		else System.arraycopy((Object[])src, srcPos, (Object[])dst, dstPos, len);
	}

	/**
	 * この buffer の保持件数を keepSize まで落とす。
	 * 古いイベントから削る。
	 *
	 * @param keepSize trim 後に残す件数
	 * @return 削除した件数
	 */
	public synchronized int trimToSize(int keepSize) {
		int currentSize = size();
		keepSize = Math.max(0, keepSize);

		if (keepSize >= currentSize) {
			return 0;
		}

		int trimCount = currentSize - keepSize;
		trimOldEvents(trimCount);
		return trimCount;
	}

	/**
	 * @return この buffer に論理的に保持されているイベント数
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
	 * 論理的な i 番目（最古 = 0、0 &lt;= i &lt; storedSize）に対応する物理インデックスを返す。
	 *
	 * <p>仕様:</p>
	 * <ul>
	 * <li>storedSize &lt; retentionLimit のとき（リングがまだ一周していない）、イベントは物理
	 *     インデックス 0..storedSize-1 に発生順で並び、nextPos == storedSize である。
	 *     よって getPos(i) == i（恒等写像）。</li>
	 * <li>storedSize == retentionLimit のとき（リングが埋まっている）、nextPos は次の書き込み先
	 *     であり同時に最古の要素を指す。よって getPos(i) == (nextPos + i) % retentionLimit。</li>
	 * </ul>
	 *
	 * <p>base = (storedSize &lt; retentionLimit) ? 0 : nextPos とおくと、両者は
	 * getPos(i) == (base + i) % retentionLimit と統一的に書ける。前者では
	 * base + i == i &lt; storedSize &lt;= capacity なので剰余が恒等になるためである。
	 * {@link #copyRange(Object, Object, int, int)} はこの統一形を前提に、
	 * 法 retentionLimit での折り返し位置を計算している。</p>
	 *
	 * <p>不変条件: storedSize &lt;= capacity かつ storedSize &lt;= retentionLimit。
	 * capacity と retentionLimit の大小関係は固定されていない
	 * （{@link #setRetentionLimit(int)} は上限だけを下げ、配列は縮めないため
	 * capacity &gt; retentionLimit になり得る）。</p>
	 *
	 * <p>返り値が物理配列の範囲内に収まる理由:</p>
	 * <ul>
	 * <li>非折り返し時は i &lt; storedSize &lt;= capacity。</li>
	 * <li>折り返し時は storedSize == retentionLimit と storedSize &lt;= capacity から
	 *     retentionLimit &lt;= capacity が従うので、法 retentionLimit の剰余は
	 *     必ず capacity 未満になる。</li>
	 * </ul>
	 */
	private int getPos(int i) {
		if (storedSize < retentionLimit) {
			return i;
		}

		return (nextPos + i) % retentionLimit;
	}

	/**
	 * CSV形式での文字列表現を生成します。
	 *
	 * <p>カラム数はヘッダ（ProposedMethodLogger.getColumnNames）と揃える必要があるため、
	 * この buffer 自身の retentionLimit ではなく、呼び出し側が指定した columns で埋める。
	 * keepk 有効時は retentionLimit が buffer ごとに異なり得るため、ここを
	 * retentionLimit にすると行ごとにカラム数がずれてしまう。</p>
	 *
	 * @param columns ヘッダの value/seqnum/thread 組の数（= 全体容量 size）
	 */
	public synchronized String toCsvString(int columns) {
		StringBuilder buf = new StringBuilder();
		buf.append(count()).append(",").append(size());
		int currentSize = size();
		for (int i = 0; i < columns; i++) {
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
	 * CSV形式での文字列表現を生成します。デバッグ用。
	 * トレース出力には、カラム数を明示できる {@link #toCsvString(int)} を使うこと。
	 */
	@Override
	public synchronized String toString() {
		return toCsvString(retentionLimit);
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
						if (o == null) {
							buf.writeStringField("id", "<GC>");
						} else {
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
