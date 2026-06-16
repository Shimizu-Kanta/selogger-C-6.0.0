package selogger.weaver;

import static org.junit.Assert.*;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.junit.Test;

import selogger.weaver.RuntimeWeaver.Mode;


public class RuntimeWeaverParametersTest {

	@Test
	public void testArgs() {
		RuntimeWeaverParameters params = new RuntimeWeaverParameters("format=omnibinary,dump=true,output=selogger-output-1");
		assertTrue(params.isOutputJsonEnabled());
		assertTrue(params.isDumpClassEnabled());
		assertEquals("selogger-output-1", params.getOutputDirname());
		assertEquals(Mode.BinaryStream, params.getMode());
		

		String today = new SimpleDateFormat("yyyyMMdd").format(new Date());
		params = new RuntimeWeaverParameters("output=selogger-output-{time}-example");
		assertNotEquals("selogger-output-{time}-example", params.getOutputDirname());
		assertTrue(params.getOutputDirname().contains(today));

		params = new RuntimeWeaverParameters("output=selogger-output-{time:}-example");
		assertEquals("selogger-output-{time:}-example", params.getOutputDirname());

		params = new RuntimeWeaverParameters("output=selogger-output-{time:yyyyMMdd}-example");
		assertEquals("selogger-output-" + today + "-example", params.getOutputDirname());

		params = new RuntimeWeaverParameters("output={time:yyyyMMdd}");
		assertEquals(today, params.getOutputDirname());
	}

	@Test
	public void testPrometParameters() {
		RuntimeWeaverParameters params =
				new RuntimeWeaverParameters("format=promet,leaverate=80");
		assertEquals(Mode.Proposed, params.getMode());
		assertEquals(80, params.getLeaveRate());

		params = new RuntimeWeaverParameters("format=proposed,leaverate=1");
		assertEquals(Mode.Proposed, params.getMode());
		assertEquals(1, params.getLeaveRate());

		params = new RuntimeWeaverParameters("leaverate=99");
		assertEquals(99, params.getLeaveRate());

		params = new RuntimeWeaverParameters("leaverate=0");
		assertEquals(80, params.getLeaveRate());

		params = new RuntimeWeaverParameters("leaverate=100");
		assertEquals(80, params.getLeaveRate());

		params = new RuntimeWeaverParameters("leaverate=-1");
		assertEquals(80, params.getLeaveRate());

		params = new RuntimeWeaverParameters("leaverate=abc");
		assertEquals(80, params.getLeaveRate());
	}

}
