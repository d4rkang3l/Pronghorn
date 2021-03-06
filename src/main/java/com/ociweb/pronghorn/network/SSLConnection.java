package com.ociweb.pronghorn.network;

import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SSLConnection {
	
	static final Logger log = LoggerFactory.getLogger(SSLConnection.class);

	protected final SSLEngine engine;
	protected final SocketChannel socketChannel;
	protected final long id;
	protected boolean isValid = true;

	protected int localRunningBytesProduced;
	private long lastNetworkBeginWait = 0;
	private int sequenceNo;

	protected boolean isDisconnecting = false;
	protected static boolean isShuttingDown =  false;
	
	protected SSLConnection(SSLEngine engine, SocketChannel socketChannel, long id ) {
		this.engine = engine;
		this.socketChannel = socketChannel;
		this.id = id;
	}
	
	public String toString() {
		return engine.getSession().toString()+" id:"+id;
	}
	
    //should only be closed by the socket writer logic or TLS handshake may be disrupted causing client to be untrusted.
	public boolean close() {
		if (isValid) {
			isValid = false;
			try {
					getSocketChannel().close();
				} catch (Throwable e) {					
			    }			
			return true;
		} 		
		return false;
	}
	
	protected HandshakeStatus closeInboundCloseOutbound(SSLEngine engine) {
		
		try {
		    engine.closeInbound();
		} catch (SSLException e) {
		    boolean debug = false;
		    if (debug) {
		    	if (!isShuttingDown) {	//if we are shutting down this is not an error.                    	
		    		log.trace("This engine was forced to close inbound, without having received the proper SSL/TLS close notification message from the peer, due to end of stream.", e);
		    	}
		    }
			
		}
		engine.closeOutbound();
		// After closeOutbound the engine will be set to WRAP state, in order to try to send a close message to the client.
		return engine.getHandshakeStatus();
	}

	
	public boolean isDisconnecting() {
		return isDisconnecting;
	}
	
	
	public long getId() {
		return this.id;
	}
	
	public SSLEngine getEngine() {
		return engine;
	}

	public SocketChannel getSocketChannel() {
		return socketChannel;
	}


	public void clearWaitingForNetwork() {
		lastNetworkBeginWait=0;
	}


	public long durationWaitingForNetwork() {
		long now = System.nanoTime();
		if (0 == lastNetworkBeginWait) {
			lastNetworkBeginWait=now;
			return 0;
		} else {
			return now - lastNetworkBeginWait;
		}
	}

	public void setSequenceNo(int seq) {
		if (seq<sequenceNo) {
			log.info("FORCE EXIT value rolled back {}for chanel{} ",seq,id);
			System.exit(-1);
		}
		//	log.info("setSequenceNo {} for chanel {}",seq,id);
		sequenceNo = seq;
	}
	
	public int getSequenceNo() {
		//  log.info("getSequenceNo {} for chanel {}",sequenceNo,id);
		return sequenceNo;
	}

	private int poolReservation=-1;
	
	public void setPoolReservation(int value) {
		poolReservation = value;
	}
	
	public int getPoolReservation() {
		return poolReservation;
	}
	
	public void clearPoolReservation() {
		poolReservation = -1;
	}


	
}
