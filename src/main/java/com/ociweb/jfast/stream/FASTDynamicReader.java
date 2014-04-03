//Copyright 2013, Nathan Tippy
//See LICENSE file for BSD license details.
//Send support requests to http://www.ociweb.com/contact
package com.ociweb.jfast.stream;

import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.loader.TemplateCatalog;
import com.ociweb.jfast.primitive.PrimitiveReader;

/*
 * Implementations of read can use this object
 * to pull the most recent parsed values of any available fields.
 * Even those in outer groups may be read however values appearing in the template
 * after the <groupId> will not have been read yet and are not available.
 * If those values are needed wait until this method is called with the
 * desired surrounding <groupId>.
 * 
 * Supports dynamic modification of the templates including:  
 * 		Field compression/operation type changes.
 * 	    Field order changes within a group.
 *      Mandatory/Optional field designation.
 *      Pulling up fields from group to the surrounding group.
 *      Pushing down fields from group to the internal group.
 * 
 * In some cases after modification the data will no longer be available
 * and unexpected results can occur.  Caution must be used whenever pulling up
 * or pushing down fields as it probably changes the meaning of the data. 
 * 
 */
public class FASTDynamicReader implements FASTDataProvider {

	private final FASTReaderDispatch readerDispatch;
	private final TemplateCatalog catalog;
		
	
	private final int[] fullScript;
	
	private final byte[] preambleData;
	private final byte preambleDataLength;
	
	private long messageCount = 0;
	//the smaller the better to make it fit inside the cache.
	private final FASTRingBuffer ringBuffer;
	
	//When setting neededSpace
	//Worst case scenario is that this is full of decimals which each need 3.
	//but for easy math we will use 4, will require a little more empty space in buffer
	//however we will not need a lookup table 
    int neededSpaceOrTemplate = -1;
    int lastCapacity = 0;
    
	//read groups field ids and build repeating lists of tokens.
	
	//only look up the most recent value read and return it to the caller.
	public FASTDynamicReader(PrimitiveReader reader, TemplateCatalog catalog, FASTRingBuffer ringBuffer, FASTReaderDispatch dispatch) {
		this.catalog = catalog;
		this.preambleDataLength=catalog.getMessagePreambleSize();
		this.preambleData = new byte[preambleDataLength];				
		this.readerDispatch = dispatch;
		this.fullScript = catalog.fullScript();
		this.ringBuffer = ringBuffer;
		this.lastCapacity = ringBuffer.availableCapacity();
		 
	}
		
    public void reset(boolean clearData) {
    	this.messageCount = 0;
    	this.readerDispatch.activeScriptCursor = 0;
    	this.readerDispatch.activeScriptLimit = 0;
    	if (clearData) {
    		this.readerDispatch.reset();
    	}
    }
	
	public long messageCount() {
		return messageCount;
	}
	
    public String toBinary(byte[] input) {
    	StringBuilder builder = new StringBuilder();
    	for(byte b:input) {
    		builder.append(Integer.toBinaryString(0xFF&b)).append(",");
    	}
    	return builder.toString();
    }
    
	
	/**
	 * Read up to the end of the next sequence or message (eg. a repeating group)
	 * 
	 * Rules for making client compatible changes to templates.
	 * - Field can be demoted to more general common value before the group.
	 * - Field can be promoted to more specific value inside sequence
	 * - Field order inside group can change but can not cross sequence boundary.
	 * - Group boundaries can be added or removed.
	 * 
	 * Note nested sequence will stop once for each of the sequences therefore at the
	 * bottom hasMore may not have any new data but is only done as a notification that
	 * the loop has completed.
	 * 
	 * @return
	 */
    

    
    //TODO: has more takes up too much time in profiler, because methods are too large!
    //TODO: dispatch is repeating the same if sequence for type, these could be grouped!
    
	public int hasMore() {
		//start new script or detect that the end of the data has been reached
		if (neededSpaceOrTemplate<0) { 
			//checking EOF first before checking for blocked queue
			if (readerDispatch.isEOF()) {
				return 0;
			}	
			//must have room to store the new template
			int req = preambleDataLength+1;
			if (lastCapacity<req) {
				lastCapacity = ringBuffer.availableCapacity();
				if (lastCapacity<req) {
					return 0x80000000;
				}
			}
			lastCapacity-=req;
			
	        //get next token id then immediately start processing the script
			///read prefix bytes if any (only used by some implementations)
			if (preambleDataLength!=0) {
				readerDispatch.dispatchPreamble(preambleData);
				ringBuffer.appendBytes(preambleData);
				
			};
			///////////////////
			
			//open message (special type of group)			
			int templateId = readerDispatch.openMessage(catalog.maxTemplatePMapSize());
			if (templateId>=0) {
				messageCount++;
			}
			int i = templateId;

			ringBuffer.appendInteger(i);//write template id at the beginning of this message
							
			//set the cursor start and stop for this template				
			readerDispatch.activeScriptCursor = catalog.getTemplateStartIdx(i); 
			readerDispatch.activeScriptLimit = catalog.getTemplateLimitIdx(i);
							
			//Worst case scenario is that this is full of decimals which each need 3.
			//but for easy math we will use 4, will require a little more empty space in buffer		    	
			//however we will not need a lookup table 
			neededSpaceOrTemplate = (readerDispatch.activeScriptLimit-readerDispatch.activeScriptCursor)<<2;
			assert(neededSpaceOrTemplate>0) : "Script must have positive value";// zero is used for unknown template
		} 
		
		if (neededSpaceOrTemplate>0) {
			if (lastCapacity<neededSpaceOrTemplate) {
				lastCapacity = ringBuffer.availableCapacity();
				if (lastCapacity<neededSpaceOrTemplate) {
					return 0x80000000;
				}
			}
			lastCapacity -= neededSpaceOrTemplate;
		}
		
		if (readerDispatch.dispatchReadByToken(ringBuffer)) {		
				ringBuffer.moveForward();
				if (readerDispatch.jumpSequence>=0) {
				    return processSequence(readerDispatch.jumpSequence); 
				}
		    
		}
		return finishTemplate();
	}

	private int finishTemplate() {
		//reached the end of the script so close and prep for the next one
		ringBuffer.moveForward();
		neededSpaceOrTemplate = -1;
		readerDispatch.closeMessage();
		return 2;//finished reading full message
	}

	private int processSequence(int i) {
		if (i>0) {	//jumping (backward) to do this sequence again.
			neededSpaceOrTemplate = 1+(i<<2);
			readerDispatch.activeScriptCursor -= i;
			return 1;//has sequence group to read
		} else {
			//finished sequence, no need to jump
			if (++readerDispatch.activeScriptCursor==readerDispatch.activeScriptLimit) {
				neededSpaceOrTemplate = -1;
				readerDispatch.closeMessage();
				return 3;//finished reading full message and the sequence
			}
			return 1;//has sequence group to read
		}
	}

	

	public byte[] prefix() {
		return preambleData;
	}
	
	public FASTRingBuffer ringBuffer() {
		return ringBuffer;
	}


}
