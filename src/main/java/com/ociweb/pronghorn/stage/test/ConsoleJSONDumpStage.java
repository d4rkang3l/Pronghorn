package com.ociweb.pronghorn.stage.test;

import java.io.PrintStream;

import com.ociweb.pronghorn.pipe.MessageSchema;
import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.pipe.stream.StreamingReadVisitor;
import com.ociweb.pronghorn.pipe.stream.StreamingReadVisitorToJSON;
import com.ociweb.pronghorn.pipe.stream.StreamingVisitorReader;
import com.ociweb.pronghorn.stage.PronghornStage;
import com.ociweb.pronghorn.stage.scheduling.GraphManager;

public class ConsoleJSONDumpStage<T extends MessageSchema> extends PronghornStage {

	private final Pipe<T> input;

	private StreamingReadVisitor visitor;
	private StreamingVisitorReader reader;
	private PrintStream out = System.out;

	public static void newInstance(GraphManager graphManager, Pipe input) {
		new ConsoleJSONDumpStage(graphManager, input);
	}
	
	public static void newInstance(GraphManager graphManager, Pipe input, PrintStream out) {
		new ConsoleJSONDumpStage(graphManager, input, out);
	}
	
	public ConsoleJSONDumpStage(GraphManager graphManager, Pipe<T> input) {
		super(graphManager, input, NONE);
		this.input = input;
	}

	public ConsoleJSONDumpStage(GraphManager graphManager, Pipe<T> input, PrintStream out) {
		this(graphManager, input);
		this.out = out;
	}

	@Override
	public void startup() {

		try{
            visitor = new StreamingReadVisitorToJSON(out) {
				@Override
				public void visitASCII(String name, long id, Appendable value) {
					assert (((CharSequence)value).length()<=input.maxAvgVarLen) : "Text is too long found "+((CharSequence)value).length();
					super.visitASCII(name, id, value);
				}
				@Override
				public void visitUTF8(String name, long id, Appendable value) {
					assert (((CharSequence)value).length()<=input.maxAvgVarLen) : "Text is too long found "+((CharSequence)value).length();
					super.visitUTF8(name, id, value);
				}
				@Override
				public void shutdown() {
					super.shutdown();
					ConsoleJSONDumpStage.this.requestShutdown();
				}
			};

			reader = new StreamingVisitorReader(input, visitor );

			reader.startup();

		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	@Override
	public void run() {
		reader.run();
	}

	@Override
	public void shutdown() {

		try{
			reader.shutdown();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	//TODO: fix this.
//	[ConsoleJSONDumpStage id:3] ERROR com.ociweb.pronghorn.stage.scheduling.ThreadPerStageScheduler - Unexpected error in ConsoleJSONDumpStage #3 which has 1 inputs and 0 outputs
//	java.lang.AssertionError: tail 0 readBase 16356 head 30
//		at com.ociweb.pronghorn.pipe.Pipe.validateInsideData(Pipe.java:700)
//		at com.ociweb.pronghorn.pipe.Pipe.markBytesReadBase(Pipe.java:712)
//		at com.ociweb.pronghorn.pipe.Pipe.releaseReadLock(Pipe.java:2783)
//		at com.ociweb.pronghorn.pipe.stream.StreamingVisitorReader.run(StreamingVisitorReader.java:87)
//		at com.ociweb.pronghorn.stage.test.ConsoleJSONDumpStage.run(ConsoleJSONDumpStage.java:64)
//		at com.ociweb.pronghorn.stage.scheduling.ThreadPerStageScheduler.runPeriodicLoop(ThreadPerStageScheduler.java:474)
//		at com.ociweb.pronghorn.stage.scheduling.ThreadPerStageScheduler.access$500(ThreadPerStageScheduler.java:17)
//		at com.ociweb.pronghorn.stage.scheduling.ThreadPerStageScheduler$3.run(ThreadPerStageScheduler.java:342)
//		at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1142)
}
