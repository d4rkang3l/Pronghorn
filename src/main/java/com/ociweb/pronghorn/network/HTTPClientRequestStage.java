package com.ociweb.pronghorn.network;

import java.util.Arrays;

import javax.net.ssl.SSLEngineResult.HandshakeStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.pronghorn.network.schema.ClientHTTPRequestSchema;
import com.ociweb.pronghorn.network.schema.NetPayloadSchema;
import com.ociweb.pronghorn.pipe.DataOutputBlobWriter;
import com.ociweb.pronghorn.pipe.Pipe;

import com.ociweb.pronghorn.stage.PronghornStage;
import com.ociweb.pronghorn.stage.scheduling.GraphManager;
import com.ociweb.pronghorn.util.Appendables;

public class HTTPClientRequestStage extends PronghornStage {

	public static final Logger logger = LoggerFactory.getLogger(HTTPClientRequestStage.class);
	
	private final Pipe<ClientHTTPRequestSchema>[] input;
	private final Pipe<NetPayloadSchema>[] output;
	private final ClientCoordinator ccm;

	private final long disconnectTimeoutMS = 10_000;  //TODO: set with param
	private long nextUnusedCheck = 0;
	

			
	private static final String implementationVersion = PronghornStage.class.getPackage().getImplementationVersion()==null?"unknown":PronghornStage.class.getPackage().getImplementationVersion();
		
	private static final byte[] EMPTY = new byte[0];
	private static final byte[] GET_BYTES = "GET".getBytes();
	private static final byte[] SPACE_SLASH_BYTES = " /".getBytes();
	private static final byte[] SPACE_BYTES = " ".getBytes();
	

	public HTTPClientRequestStage(GraphManager graphManager, 	
			ClientCoordinator ccm,
            Pipe<ClientHTTPRequestSchema>[] input,
            Pipe<NetPayloadSchema>[] output
            ) {
		super(graphManager, input, output);
		this.input = input;
		this.output = output;
		this.ccm = ccm;
		
		//TODO: we have a bug here detecting EOF so this allows us to shutdown until its found.
		GraphManager.addNota(graphManager, GraphManager.PRODUCER, GraphManager.PRODUCER, this);
	}
	
	
	@Override
	public void startup() {
		
		super.startup();		
		
	}
	
	@Override
	public void shutdown() {
		
		int i = output.length;
		while (--i>=0) {
			Pipe.spinBlockForRoom(output[i], Pipe.EOF_SIZE);
			Pipe.publishEOF(output[i]);
		}
	}
	
	@Override
	public void run() {
		boolean didWork;
		
		//does not need to be that acurrate so do it outside the loop.
		
		final long now = System.currentTimeMillis();
	
		do {
			didWork = false;
			int i = input.length;
			while (--i>=0) {
				didWork |= processMessagesForPipe(i, now);
			}
			
			//check if some connections have not been used and can be closed.
			if (now>nextUnusedCheck) {
				//TODO: URGENT, this is killing of valid connections, but why? debug
				//	closeUnusedConnections();
				nextUnusedCheck = now+disconnectTimeoutMS;
			}
	
		} while (didWork);
		
	}


	private void closeUnusedConnections() {
		long now;
		ClientConnection con = ccm.nextValidConnection();
		final ClientConnection firstCon = con;					
		while (null!=con) {
			con = ccm.nextValidConnection();
			
			long unused = now = con.getLastUsedTime();
			
			if (unused>disconnectTimeoutMS) {
				
				Pipe<NetPayloadSchema> pipe = output[con.requestPipeLineIdx()];
				if (Pipe.hasRoomForWrite(pipe)) {
					//close the least used connection
					cleanCloseConnection(con, pipe);				
				}
				
			}
			
			if (firstCon==con) {
				break;
			}
		}
	}
	
	protected boolean processMessagesForPipe(int activePipe, long now) {
		
		
		    Pipe<ClientHTTPRequestSchema> requestPipe = input[activePipe];
		    	  
		    boolean didWork = false;

		    
		    
	///	    logger.info("send for active pipe {} has content to read {} ",activePipe,Pipe.hasContentToRead(requestPipe));
		    
	        if (Pipe.hasContentToRead(requestPipe)         
	             && hasOpenConnection(requestPipe, output, ccm)
	             ){
	  	    	        	
	        	// logger.info("send for active pipe {}",activePipe);
	        	
	        	//Need peek to know if this will block.
	 	        		        	
	            final int msgIdx = Pipe.takeMsgIdx(requestPipe);
	            
	            didWork = true;
	        
	     //       logger.info("write client for {}",msgIdx);
	            
	            //TODO: remove this switch???
				switch (msgIdx) {
							case -1:
								logger.info("Received shutdown message");								
								processShutdownLogic(requestPipe);
								return false;
								
							case ClientHTTPRequestSchema.MSG_CLOSE_104:															
								cleanCloseConnection(activeConnection, output[activeConnection.requestPipeLineIdx()]);						
								
		                	break;		
	            			case ClientHTTPRequestSchema.MSG_FASTHTTPGET_200:
	            				//logger.info("write FAST GET");
	            				processFastGetLogic(now, requestPipe);
	    	            	break;
	            	        case ClientHTTPRequestSchema.MSG_HTTPGET_100:
	            	        	//logger.info("write GET");
	            				processGetLogic(now, requestPipe);
	            				break;
	            			case ClientHTTPRequestSchema.MSG_HTTPPOST_101:
	            				processsPostLogic(now, requestPipe);
	            				break;	 
	    	           	    default:
	    	           	    	throw new UnsupportedOperationException("Unexpected Message Idx");	            
	            }
			
				
				Pipe.confirmLowLevelRead(requestPipe, Pipe.sizeOf(requestPipe, msgIdx));
				Pipe.releaseReadLock(requestPipe);	


	        }	            
		return didWork;
	}


	private void processsPostLogic(long now, Pipe<ClientHTTPRequestSchema> requestPipe) {
		{
            
      
		    	ClientConnection clientConnection = activeConnection;
		    	clientConnection.setLastUsedTime(now);
		    	int outIdx = clientConnection.requestPipeLineIdx();
		    					                  	
		    	clientConnection.incRequestsSent();//count of messages can only be done here.
				Pipe<NetPayloadSchema> outputPipe = output[outIdx];
		    
		        if (Pipe.hasRoomForWrite(outputPipe) ) {
		        	
		        	int pSize = Pipe.addMsgIdx(outputPipe, NetPayloadSchema.MSG_PLAIN_210);
		        	Pipe.addLongValue(clientConnection.id, outputPipe); //NetPayloadSchema.MSG_PLAIN_210_FIELD_CONNECTIONID_201, clientConnection.id);
		        	Pipe.addLongValue(System.currentTimeMillis(), outputPipe);
		        	
		        	DataOutputBlobWriter<NetPayloadSchema> activeWriter = Pipe.outputStream(outputPipe);
		        	DataOutputBlobWriter.openField(activeWriter);
		        			                
		        	DataOutputBlobWriter.encodeAsUTF8(activeWriter,"POST");
		        	
		        	int userId = Pipe.takeInt(requestPipe);
		        	int port   = Pipe.takeInt(requestPipe);
		        	int hostMeta = Pipe.takeRingByteMetaData(requestPipe);
		        	int hostLen  = Pipe.takeRingByteLen(requestPipe);
		        	int hostPos = Pipe.bytePosition(hostMeta, requestPipe, hostLen);
		        	
		          	int meta = Pipe.takeRingByteMetaData(requestPipe); //ClientHTTPRequestSchema.MSG_HTTPPOST_101_FIELD_PATH_3
		        	int len  = Pipe.takeRingByteLen(requestPipe);
		            int first = Pipe.bytePosition(meta, requestPipe, len);					                	
		        
		            boolean prePendSlash = (0==len) || ('/' != Pipe.byteBackingArray(meta, requestPipe)[first&Pipe.blobMask(requestPipe)]);
		      
					if (prePendSlash) { //NOTE: these can be pre-coverted to bytes so we need not convert on each write. may want to improve.
						DataOutputBlobWriter.encodeAsUTF8(activeWriter," /");
					} else {
						DataOutputBlobWriter.encodeAsUTF8(activeWriter," ");
					}
					
					//Reading from UTF8 field and writing to UTF8 encoded field so we are doing a direct copy here.
					Pipe.readBytes(requestPipe, activeWriter, meta, len);//, ClientHTTPRequestSchema.MSG_HTTPPOST_101_FIELD_PATH_3, activeWriter);
					
					
					int payloadMeta = Pipe.takeRingByteMetaData(requestPipe); //MSG_HTTPPOST_101_FIELD_PAYLOAD_5
					int payloadLen  = Pipe.takeRingByteMetaData(requestPipe);
					
					
					//For chunked must pass in -1

					//TODO: this field can no be any loger than 4G so we cant post anything larger than that
					//TODO: we also need support for chunking which will need multiple mesage fragments
					//TODO: need new message type for chunking/streaming post
					
		    		final byte[] hostBack = Pipe.byteBackingArray(hostMeta, requestPipe);//, ClientHTTPRequestSchema.MSG_HTTPGET_100_FIELD_HOST_2);
		    		final int hostMask    = Pipe.blobMask(requestPipe);	
					
					finishWritingHeader(hostBack, hostPos, hostLen, hostMask, activeWriter, implementationVersion, payloadLen);
					
					Pipe.readBytes(requestPipe, activeWriter, payloadMeta, payloadLen); //MSG_HTTPPOST_101_FIELD_PAYLOAD_5
					
		        	DataOutputBlobWriter.closeLowLevelField(activeWriter);//, NetPayloadSchema.MSG_PLAIN_210_FIELD_PAYLOAD_204);
		        		
		        	Pipe.confirmLowLevelWrite(outputPipe,pSize);
		        	Pipe.publishWrites(outputPipe);
		        					                	
		        } else {
		        	System.err.println("unable to write");
		        	throw new RuntimeException("Unable to send request, outputPipe is full");
		        }
				
       		
		}
	}


	private void processGetLogic(long now, Pipe<ClientHTTPRequestSchema> requestPipe) {
		{
		        
		    	//logger.info("request sent to connection id {} for host {}, port {}, userid {} ",connectionId, activeHost, port, userId);
		    	
		    	ClientConnection clientConnection = activeConnection;
  
		        	clientConnection.setLastUsedTime(now);
		        	int outIdx = clientConnection.requestPipeLineIdx();
		        	
		        	//logger.info("sent get request down pipe {} ",outIdx);
		        	
		        	clientConnection.incRequestsSent();//count of messages can only be done here.
					assert(Pipe.hasRoomForWrite(output[outIdx]));

					Pipe<NetPayloadSchema> outputPipe = output[outIdx];
	
				long pos = Pipe.workingHeadPosition(outputPipe);
					
		               	int pSize = Pipe.addMsgIdx(outputPipe, NetPayloadSchema.MSG_PLAIN_210);
		               	
		               	Pipe.addLongValue(clientConnection.id, outputPipe); //, NetPayloadSchema.MSG_PLAIN_210_FIELD_CONNECTIONID_201, clientConnection.id);
		               	Pipe.addLongValue(System.currentTimeMillis(), outputPipe);
		               	Pipe.addLongValue(0, outputPipe); // NetPayloadSchema.MSG_PLAIN_210_FIELD_POSITION_206, 0);
		             	
		             	
		            	DataOutputBlobWriter<NetPayloadSchema> activeWriter = Pipe.outputStream(outputPipe);
		            	DataOutputBlobWriter.openField(activeWriter);
						
		            	DataOutputBlobWriter.encodeAsUTF8(activeWriter,"GET");
		            	
		            	int userId = Pipe.takeInt(requestPipe);
		            	int port   = Pipe.takeInt(requestPipe);
		            	int hostMeta = Pipe.takeRingByteMetaData(requestPipe);
		            	int hostLen  = Pipe.takeRingByteLen(requestPipe);
		            	int hostPos = Pipe.bytePosition(hostMeta, requestPipe, hostLen);
		            	
		              	int meta = Pipe.takeRingByteMetaData(requestPipe); //ClientHTTPRequestSchema.MSG_HTTPGET_100_FIELD_PATH_3
		            	int len  = Pipe.takeRingByteLen(requestPipe);
		                int first = Pipe.bytePosition(meta, requestPipe, len);					                	
		            
		                boolean prePendSlash = (0==len) || ('/' != Pipe.byteBackingArray(meta, requestPipe)[first&Pipe.blobMask(requestPipe)]);

						if (prePendSlash) { //NOTE: these can be pre-coverted to bytes so we need not convert on each write. may want to improve.
							DataOutputBlobWriter.encodeAsUTF8(activeWriter," /");
						} else {
							DataOutputBlobWriter.encodeAsUTF8(activeWriter," ");
						}
						
						//Reading from UTF8 field and writing to UTF8 encoded field so we are doing a direct copy here.
						Pipe.readBytes(requestPipe, activeWriter, meta, len);//, ClientHTTPRequestSchema.MSG_HTTPGET_100_FIELD_PATH_3, activeWriter);
				
		        		final byte[] hostBack = Pipe.byteBackingArray(hostMeta, requestPipe);//, ClientHTTPRequestSchema.MSG_HTTPGET_100_FIELD_HOST_2);
		        		final int hostMask    = Pipe.blobMask(requestPipe);	
		        		
						
						finishWritingHeader(hostBack, hostPos, hostLen, hostMask, activeWriter, implementationVersion, 0);
		            
		            					                	
		            	DataOutputBlobWriter.closeLowLevelField(activeWriter);//, NetPayloadSchema.MSG_PLAIN_210_FIELD_PAYLOAD_204);
		
		            	
		            	Pipe.confirmLowLevelWrite(outputPipe,pSize);
		            	Pipe.publishWrites(outputPipe);
		       

		}
	}


	private void processFastGetLogic(long now, Pipe<ClientHTTPRequestSchema> requestPipe) {
		{
		        
		    	//logger.info("request sent to connection id {} for host {}, port {}, userid {} ",connectionId, activeHost, port, userId);
		    	
		    	    ClientConnection clientConnection = activeConnection;
  
		        	clientConnection.setLastUsedTime(now);
		        	int outIdx = clientConnection.requestPipeLineIdx();
			        	
		        	clientConnection.incRequestsSent();//count of messages can only be done here.
					assert(Pipe.hasRoomForWrite(output[outIdx]));
				
			   	    publishGet(requestPipe, clientConnection, output[outIdx]);
		    
		       

		}
	}


	private void publishGet(Pipe<ClientHTTPRequestSchema> requestPipe, ClientConnection clientConnection,
			Pipe<NetPayloadSchema> outputPipe) {
		
		long pos = Pipe.workingHeadPosition(outputPipe);
		
		int pSize = Pipe.addMsgIdx(outputPipe, NetPayloadSchema.MSG_PLAIN_210); 	
		  
		Pipe.addLongValue(clientConnection.id, outputPipe); //, NetPayloadSchema.MSG_PLAIN_210_FIELD_CONNECTIONID_201, clientConnection.id);
		Pipe.addLongValue(System.currentTimeMillis(), outputPipe);
		Pipe.addLongValue(0, outputPipe); // NetPayloadSchema.MSG_PLAIN_210_FIELD_POSITION_206, 0);
		 							                 	
		
		DataOutputBlobWriter<NetPayloadSchema> activeWriter = Pipe.outputStream(outputPipe);
		DataOutputBlobWriter.openField(activeWriter);
		activeWriter.write((int)'G');
		activeWriter.write((int)'E');
		activeWriter.write((int)'T');
		//DataOutputBlobWriter.write(activeWriter,GET_BYTES, 0, GET_BYTES.length);
		
		int userId = Pipe.takeInt(requestPipe);
		int port   = Pipe.takeInt(requestPipe);
		int hostMeta = Pipe.takeRingByteMetaData(requestPipe);
		int hostLen  = Pipe.takeRingByteLen(requestPipe);
		int hostPos = Pipe.bytePosition(hostMeta, requestPipe, hostLen);
		long connId = Pipe.takeLong(requestPipe);
								                	
		
		int meta = Pipe.takeRingByteMetaData(requestPipe); //ClientHTTPRequestSchema.MSG_FASTHTTPGET_200_FIELD_PATH_3
		int len  = Pipe.takeRingByteLen(requestPipe);
		int first = Pipe.bytePosition(meta, requestPipe, len);
								                						                	
		boolean prePendSlash = (0==len) || ('/' != Pipe.byteBackingArray(meta, requestPipe)[first&Pipe.blobMask(requestPipe)]);  
		
		if (prePendSlash) { //NOTE: these can be pre-coverted to bytes so we need not convert on each write. may want to improve.
			activeWriter.write((int)' ');
			activeWriter.write((int)'/');
			//DataOutputBlobWriter.write(activeWriter,SPACE_SLASH_BYTES, 0, SPACE_SLASH_BYTES.length);
		} else {
			activeWriter.write((int)' ');
			//DataOutputBlobWriter.write(activeWriter,SPACE_BYTES, 0, SPACE_BYTES.length);
		}
		
		//Reading from UTF8 field and writing to UTF8 encoded field so we are doing a direct copy here.
		Pipe.readBytes(requestPipe, activeWriter, meta, len);//, ClientHTTPRequestSchema.MSG_FASTHTTPGET_200_FIELD_PATH_3, activeWriter);
		
		final byte[] hostBack = Pipe.byteBackingArray(hostMeta, requestPipe);//, ClientHTTPRequestSchema.MSG_FASTHTTPGET_200_FIELD_HOST_2);
		final int hostMask    = Pipe.blobMask(requestPipe);	
		
		finishWritingHeader(hostBack, hostPos, hostLen, hostMask, activeWriter, implementationVersion, 0);
  
		
		DataOutputBlobWriter.closeLowLevelField(activeWriter);//, NetPayloadSchema.MSG_PLAIN_210_FIELD_PAYLOAD_204);

		Pipe.confirmLowLevelWrite(outputPipe,pSize);
		Pipe.publishWrites(outputPipe);
		
		

	}


	private void processShutdownLogic(Pipe<ClientHTTPRequestSchema> requestPipe) {
		ClientConnection connectionToKill = ccm.nextValidConnection();
		final ClientConnection firstToKill = connectionToKill;					
		while (null!=connectionToKill) {								
			connectionToKill = ccm.nextValidConnection();
			
			//must send handshake request down this pipe
			int pipeId = connectionToKill.requestPipeLineIdx();
			
			cleanCloseConnection(connectionToKill, output[pipeId]);
												
			if (firstToKill == connectionToKill) {
				break;//done
			}
		}
		
		requestShutdown();
		Pipe.confirmLowLevelRead(requestPipe, Pipe.EOF_SIZE);
		Pipe.releaseReadLock(requestPipe);
	}


	private static void cleanCloseConnection(ClientConnection connectionToKill, Pipe<NetPayloadSchema> pipe) {
		
		//logger.info("CLIENT SIDE BEGIN CONNECTION CLOSE");

		//do not close that will be done by last stage
		//must be done first before we send the message
		connectionToKill.beginDisconnect();

		if (Pipe.hasRoomForWrite(pipe) ) {
			int size = Pipe.addMsgIdx(pipe, NetPayloadSchema.MSG_DISCONNECT_203);
			Pipe.addLongValue(connectionToKill.getId(), pipe);//   NetPayloadSchema.MSG_DISCONNECT_203_FIELD_CONNECTIONID_201, connectionToKill.getId());
			Pipe.confirmLowLevelWrite(pipe, size);
			Pipe.publishWrites(pipe);
		} else {
			throw new RuntimeException("Unable to send request, outputPipe is full");
		}
	}




	ClientConnection activeConnection =  null;
	
	//has side effect fo storing the active connectino as a member so it neeed not be looked up again later.
	private boolean hasOpenConnection(Pipe<ClientHTTPRequestSchema> requestPipe, 
											Pipe<NetPayloadSchema>[] output, ClientCoordinator ccm) {

		int msgIdx = Pipe.peekInt(requestPipe);
		
		if (Pipe.peekMsg(requestPipe, -1)) {
			return hasRoomForEOF(output);
		}

		//logger.info("out idx {} requestPipe {} ",outIdx, requestPipe);
		
		
		assert (msgIdx==ClientHTTPRequestSchema.MSG_FASTHTTPGET_200 || msgIdx==ClientHTTPRequestSchema.MSG_HTTPGET_100 ) : "bad msgIdx of "+msgIdx+" at "+Pipe.getWorkingTailPosition(requestPipe)+"  "+requestPipe;
		
		int userId = Pipe.peekInt(requestPipe,   1); //user id always after the msg idx
		int port = Pipe.peekInt(requestPipe,     2); //port is always after the userId; 
			
		int hostMeta = Pipe.peekInt(requestPipe, 3); //host is always after port
 		int hostLen  = Pipe.peekInt(requestPipe, 4); //host is always after port

 		int hostPos  = Pipe.convertToPosition(hostMeta, requestPipe);		
 		byte[] hostBack = Pipe.byteBackingArray(hostMeta, requestPipe);
 		int hostMask = Pipe.blobMask(requestPipe);
 		
 		long connectionId;

 		if (Pipe.peekMsg(requestPipe, ClientHTTPRequestSchema.MSG_FASTHTTPGET_200)) {
 			connectionId = Pipe.peekLong(requestPipe, 5);//do not do lookup if it was already provided.
 		//	System.err.println("loaded connection "+connectionId);
 		} else {
     		connectionId = ccm.lookup(hostBack,hostPos,hostLen,hostMask, port, userId);
		//	System.err.println("first lookup connection "+connectionId);
 		}
		
 		
		activeConnection = ClientCoordinator.openConnection(ccm, hostBack, hostPos, hostLen, hostMask, port, userId, output, connectionId);
				
		
		if (null != activeConnection) {
			
			if (ccm.isTLS) {
				
				//If this connection needs to complete a hanshake first then do that and do not send the request content yet.
				HandshakeStatus handshakeStatus = activeConnection.getEngine().getHandshakeStatus();
				if (HandshakeStatus.FINISHED!=handshakeStatus && HandshakeStatus.NOT_HANDSHAKING!=handshakeStatus /* && HandshakeStatus.NEED_WRAP!=handshakeStatus*/) {
					
					
				//	System.err.println("no hanshake "+activeConnection.id+" status "+handshakeStatus);
					
					
					activeConnection = null;
					
					return false;
				}
				if ( HandshakeStatus.NEED_WRAP==handshakeStatus) {
					
					//TODO: send wrap request???
					
				}
				
				
			}
			
		} else {
			//this happens often when the profiler is running due to contention for sockets.
			
			//"Has no room" for the new connection so we request that the oldest connection is closed.
			
			//instead of doing this (which does not work) we will just wait by returning false.
//			ClientConnection connectionToKill = (ClientConnection)ccm.get( -connectionId, 0);
//			if (null!=connectionToKill) {
//				Pipe<NetPayloadSchema> pipe = output[connectionToKill.requestPipeLineIdx()];
//				if (PipeWriter.hasRoomForWrite(pipe)) {
//					//close the least used connection
//					cleanCloseConnection(connectionToKill, pipe);				
//				}
//			}
		
			//System.err.println("no connection");
			return false;
		}
		
		
		int outIdx = activeConnection.requestPipeLineIdx(); //this should be done AFTER any handshake logic
		if (!Pipe.hasRoomForWrite(output[outIdx])) {
			//System.err.println("no room for write to "+pipe);
			return false;
		}
		return true;
	}


	public static boolean hasRoomForEOF(Pipe<NetPayloadSchema>[] output) {
		//all outputs must have room for EOF processing
		int i = output.length;
		while (--i>=0) {
			if (!Pipe.hasRoomForWrite(output[i])) {
				return false;
			}
		}
		return true;
	}

	private final static byte[] REV11_AND_HOST = " HTTP/1.1\r\nHost: ".getBytes();
	private final static byte[] LINE_AND_USER_AGENT = "\r\nUser-Agent: Pronghorn/".getBytes();	
	private final static byte[] CONNECTION_KEEP_ALIVE_END = "\r\nConnection: keep-alive\r\n\r\n".getBytes();
	private final static byte[] CONNECTION_CLOSE_END = "\r\nConnection: close\r\n\r\n".getBytes();
	private final static byte[] CONTENT_LENGTH = "\r\nContent-Length: ".getBytes();
	private final static byte[] CONTENT_CHUNKED = "\r\nTransfer-Encoding: chunked".getBytes();
	
	
	public static void finishWritingHeader(byte[] hostBack, int hostPos, int hostLen, int hostMask,
			                               DataOutputBlobWriter<NetPayloadSchema> writer, CharSequence implementationVersion, long length) {
		
		DataOutputBlobWriter.write(writer, REV11_AND_HOST, 0, REV11_AND_HOST.length); //encodeAsUTF8(writer," HTTP/1.1\r\nHost: ");
		DataOutputBlobWriter.write(writer,hostBack,hostPos,hostLen,hostMask);
		
	//	DataOutputBlobWriter.write(writer, LINE_AND_USER_AGENT, 0, LINE_AND_USER_AGENT.length, Integer.MAX_VALUE);//DataOutputBlobWriter.encodeAsUTF8(writer,"\r\nUser-Agent: Pronghorn/");
	//	DataOutputBlobWriter.encodeAsUTF8(writer,implementationVersion);

		if (length>0) {
			DataOutputBlobWriter.write(writer, CONTENT_LENGTH, 0, CONTENT_LENGTH.length);
			Appendables.appendValue(writer, length); //does the same as below...			
			//DataOutputBlobWriter.encodeAsUTF8(writer,"\r\nContent-Length: "+Long.toString(length));
		} else if (length<0) {
			DataOutputBlobWriter.write(writer, CONTENT_CHUNKED, 0, CONTENT_CHUNKED.length);
		}
		
		
		DataOutputBlobWriter.write(writer, CONNECTION_KEEP_ALIVE_END, 0, CONNECTION_KEEP_ALIVE_END.length);//DataOutputBlobWriter.encodeAsUTF8(writer,"\r\nConnection: keep-alive\r\n\r\n"); //double \r\b marks the end of the header
	
		//TODO: is server closing too early, need to send response first? TODO: still not working.
	//	DataOutputBlobWriter.write(writer, CONNECTION_CLOSE_END, 0, CONNECTION_CLOSE_END.length, Integer.MAX_VALUE);
		
	}
	
	
	

}
