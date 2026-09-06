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

	@Test
	public void testKeepKParameter() {
		// 未指定なら既定の false。
		RuntimeWeaverParameters params = new RuntimeWeaverParameters("format=promet,size=1000");
		assertFalse(params.getKeepK());

		params = new RuntimeWeaverParameters("format=promet,keepk=true");
		assertTrue(params.getKeepK());

		params = new RuntimeWeaverParameters("format=promet,keepk=false");
		assertFalse(params.getKeepK());

		// Boolean.parseBoolean と同じく大文字小文字は区別しない。
		params = new RuntimeWeaverParameters("keepk=TRUE");
		assertTrue(params.getKeepK());

		params = new RuntimeWeaverParameters("keepk=True");
		assertTrue(params.getKeepK());

		// 不正値は既定の false に倒す（showbuffersize= と同じ扱い）。
		params = new RuntimeWeaverParameters("keepk=yes");
		assertFalse(params.getKeepK());

		params = new RuntimeWeaverParameters("keepk=1");
		assertFalse(params.getKeepK());

		params = new RuntimeWeaverParameters("keepk=");
		assertFalse(params.getKeepK());

		// 他のパラメータと併用できる。
		params = new RuntimeWeaverParameters("format=promet,size=1000,leaverate=90,keepk=true,output=out");
		assertEquals(Mode.Proposed, params.getMode());
		assertEquals(1000, params.getBufferSize());
		assertEquals(90, params.getLeaveRate());
		assertTrue(params.getKeepK());
	}

}
