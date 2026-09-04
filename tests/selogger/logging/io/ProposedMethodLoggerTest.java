package selogger.logging.io;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import selogger.logging.io.ProposedMethodLogger.PrometObjectRecordingStrategy;
import selogger.logging.util.JsonBuffer;

public class ProposedMethodLoggerTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private ProposedMethodLogger newLogger(int bufferSize, int leaveRate) throws Exception {
		File trace = folder.newFile("trace-" + bufferSize + "-" + leaveRate + ".json");
		return new ProposedMethodLogger(trace, bufferSize, leaveRate, false,
				PrometObjectRecordingStrategy.Strong, true, null);
	}

	@SuppressWarnings("unchecked")
	private ArrayList<ProposedMethodBuffer> getBuffers(ProposedMethodLogger logger) throws Exception {
		Field f = ProposedMethodLogger.class.getDeclaredField("buffers");
		f.setAccessible(true);
		return (ArrayList<ProposedMethodBuffer>) f.get(logger);
	}

	private int getTotalRecords(ProposedMethodLogger logger) throws Exception {
		Field f = ProposedMethodLogger.class.getDeclaredField("totalRecords");
		f.setAccessible(true);
		return ((Integer) f.get(logger)).intValue();
	}

	private int sumRecord(ArrayList<ProposedMethodBuffer> buffers) {
		int sum = 0;
		for (ProposedMethodBuffer b: buffers) {
			if (b != null) sum += b.size();
		}
		return sum;
	}

	private long sumFreq(ArrayList<ProposedMethodBuffer> buffers) {
		long sum = 0;
		for (ProposedMethodBuffer b: buffers) {
			if (b != null) sum += b.count();
		}
		return sum;
	}

	private JsonNode toJson(ProposedMethodBuffer buf) throws Exception {
		JsonBuffer json = new JsonBuffer();
		json.writeStartObject();
		buf.writeJson(json, false);
		json.writeEndObject();
		return new ObjectMapper().readTree(json.toString());
	}

	@Test
	public void testGlobalTrimUsesTotalRecordCapacity() throws Exception {
		ProposedMethodLogger logger = newLogger(10, 80);

		// dataId 0 has many records.
		for (int i = 0; i < 8; i++) {
			logger.recordEvent(0, i);
		}
		// dataId 1 has a small number of records.
		logger.recordEvent(1, 100);
		logger.recordEvent(1, 101);

		// The next record makes total records exceed the global capacity.
		// Before adding it, the logger trims total records from 10 to 8.
		logger.recordEvent(2, 200);

		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		ProposedMethodBuffer b0 = buffers.get(0);
		ProposedMethodBuffer b1 = buffers.get(1);
		ProposedMethodBuffer b2 = buffers.get(2);

		Assert.assertEquals(8L, b0.count());
		Assert.assertEquals(2L, b1.count());
		Assert.assertEquals(1L, b2.count());

		// targetTotal is 10 * 80% = 8 before adding dataId 2.
		// Small buffer dataId 1 is kept; large buffer dataId 0 is trimmed from 8 to 6.
		Assert.assertEquals(6, b0.size());
		Assert.assertEquals(2, b1.size());
		Assert.assertEquals(1, b2.size());

		Assert.assertEquals(11L, sumFreq(buffers));
		Assert.assertEquals(9, sumRecord(buffers));
		Assert.assertEquals(9, getTotalRecords(logger));
		Assert.assertTrue("global trim should make freq_sum larger than record_sum", sumFreq(buffers) > sumRecord(buffers));
		Assert.assertTrue("global record sum must not exceed global size", sumRecord(buffers) <= 10);

		JsonNode b0Json = toJson(b0);
		Assert.assertEquals(8L, b0Json.get("freq").asLong());
		Assert.assertEquals(6, b0Json.get("record").asInt());
		Assert.assertEquals(6, b0Json.get("value").size());

		// Oldest values 0 and 1 are removed from dataId 0. Newest values remain in order.
		for (int i = 0; i < 6; i++) {
			Assert.assertEquals(i + 2, b0Json.get("value").get(i).asInt());
		}
	}

	@Test
	public void testGlobalTrimPreservesSmallBuffersAndBalancesLargeBuffers() throws Exception {
		ProposedMethodLogger logger = newLogger(10, 80);

		// total before final add: dataId0=4, dataId1=4, dataId2=2, total=10
		for (int i = 0; i < 4; i++) logger.recordEvent(0, i);
		for (int i = 0; i < 4; i++) logger.recordEvent(1, 100 + i);
		for (int i = 0; i < 2; i++) logger.recordEvent(2, 200 + i);

		// Triggers global trim to targetTotal=8, then adds one record to dataId3.
		logger.recordEvent(3, 300);

		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		ProposedMethodBuffer b0 = buffers.get(0);
		ProposedMethodBuffer b1 = buffers.get(1);
		ProposedMethodBuffer b2 = buffers.get(2);
		ProposedMethodBuffer b3 = buffers.get(3);

		// cap becomes 3: [4,4,2] -> [3,3,2], then dataId3 gets one record.
		Assert.assertEquals(3, b0.size());
		Assert.assertEquals(3, b1.size());
		Assert.assertEquals(2, b2.size());
		Assert.assertEquals(1, b3.size());
		Assert.assertEquals(9, sumRecord(buffers));
		Assert.assertEquals(11L, sumFreq(buffers));
		Assert.assertTrue(sumRecord(buffers) <= 10);

		JsonNode b0Json = toJson(b0);
		Assert.assertEquals(4L, b0Json.get("freq").asLong());
		Assert.assertEquals(3, b0Json.get("record").asInt());
		Assert.assertEquals(1, b0Json.get("value").get(0).asInt());
		Assert.assertEquals(2, b0Json.get("value").get(1).asInt());
		Assert.assertEquals(3, b0Json.get("value").get(2).asInt());

		JsonNode b2Json = toJson(b2);
		Assert.assertEquals(2L, b2Json.get("freq").asLong());
		Assert.assertEquals("small buffer should be preserved", 2, b2Json.get("record").asInt());
		Assert.assertEquals(200, b2Json.get("value").get(0).asInt());
		Assert.assertEquals(201, b2Json.get("value").get(1).asInt());
	}

	@Test
	public void testRepeatedRecordsKeepGlobalRecordSumUnderLimit() throws Exception {
		ProposedMethodLogger logger = newLogger(10, 80);

		for (int i = 0; i < 30; i++) {
			logger.recordEvent(i % 5, i);
		}

		ArrayList<ProposedMethodBuffer> buffers = getBuffers(logger);
		int recordSum = sumRecord(buffers);
		long freqSum = sumFreq(buffers);

		Assert.assertEquals(30L, freqSum);
		Assert.assertTrue("global record sum must not exceed global size", recordSum <= 10);
		Assert.assertTrue("global trimming should have removed some records", freqSum > recordSum);
		Assert.assertEquals(recordSum, getTotalRecords(logger));
	}
}
