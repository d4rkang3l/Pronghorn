package com.ociweb.jfast.primitive;

import com.ociweb.jfast.error.FASTException;





/**
 * PrimitiveWriter
 * 
 * Must be final and not implement any interface or be abstract.
 * In-lining the primitive methods of this class provides much
 * of the performance needed by this library.
 * 
 * 
 * @author Nathan Tippy
 *
 */

public final class PrimitiveWriter {

    //TODO: there is some write PMAP bug that becomes obvious by changing this very small.
    private static final int BLOCK_SIZE = 256;// in bytes
    private static final int BLOCK_SIZE_LAZY = (BLOCK_SIZE*3)+(BLOCK_SIZE>>1);
    
	private final FASTOutput output;
	
	/*
	 * TODO:   Add constant for NO_FIELD_ID and test against kryo.
	 * 
	 * 
	 * 
	 */
	
	final byte[] buffer;
	private int position;
	private int limit;
	
	//TODO: due to the complexity here a stack of longs may work much better!
	private final int[] safetyStackPosition; //location where the last byte was written to the pmap as bits are written
	private final int[] safetyStackFlushIdx; //location (in skip list) where location of stopBytes+1 and end of max pmap length is found.
	private final byte[] safetyStackTemp; //working bit position 1-7 for next/last bit to be written.
	private int   safetyStackDepth;       //maximum depth of the stacks above
	private byte pMapIdxWorking = 7;
	private byte pMapByteAccum = 0;
	
	
	//these 3 fields are probably their own mini class
	private final int[] flushSkips;//list of all skip nodes produced at the end of pmaps, may grow large with poor templates.
	private int   flushSkipsIdxLimit; //where we add the new one, end of the list
	private int   flushSkipsIdxPos;//next limit to use. as skips are consumed this pointer moves forward.

	
	private long totalWritten;
	
	private final boolean minimizeLatency;
	    
    int nextBlockSize = -1;
    int nextBlockOffset = -1; 
    int pendingPosition = 0;
    
    
	public PrimitiveWriter(FASTOutput output) {
		this(4096,output,128, false);
	}
	
	public PrimitiveWriter(int initBufferSize, FASTOutput output, int maxGroupCount, boolean minimizeLatency) {
		
		if (initBufferSize<BLOCK_SIZE_LAZY*2) {
			initBufferSize = BLOCK_SIZE_LAZY*2;
		}
		
		
		this.buffer = new byte[initBufferSize];
		this.position = 0;
		this.limit = 0;
		this.minimizeLatency = minimizeLatency;
		//NOTE: may be able to optimize this so these 3 are shorter
		safetyStackPosition = new int[maxGroupCount];
		safetyStackFlushIdx = new int[maxGroupCount];
		safetyStackTemp = new byte[maxGroupCount];
		//max total groups
		flushSkips = new int[maxGroupCount*2];//this may grow very large, to fields per group
		
		this.output = output;
		
		output.init(new DataTransfer(this));
	}
	

	
	public void reset() {
		this.position = 0;
		this.limit = 0;
		this.safetyStackDepth = 0;
		this.pMapIdxWorking = 7;
		this.pMapByteAccum = 0;
		this.flushSkipsIdxLimit = 0;
		this.flushSkipsIdxPos = 0;
		this.totalWritten = 0;
		
	}
	
    public long totalWritten() {
    	return totalWritten;
    }
 	

    
	public int nextBlockSize() {
		//return block size if the block is available
		if (nextBlockSize > 0) {
			return nextBlockSize;
		}
		//block was not available so build it
		int flushTo = computeFlushToIndex();		
		
		int toFlush = flushTo-position;
		if (0==toFlush) {
			nextBlockSize = 0;
			nextBlockOffset = -1; 
			//no need to do any work.
			return 0;
		} else if (flushSkipsIdxPos==flushSkipsIdxLimit) {
			
			//all the data we have lines up with the end of the skip limit			
			//nothing to skip so flush the full block
			
			nextBlockOffset = position;
			
			int avail = flushTo - position;
			if (avail >= BLOCK_SIZE) {
				nextBlockSize = BLOCK_SIZE;
				pendingPosition = position+BLOCK_SIZE;
			} else { 
				//not enough to fill a block so we must flush what we have.
				nextBlockSize = avail;
				pendingPosition = position+avail;
			}
		} else {
			buildNextBlockWithSkips();
		}
		return nextBlockSize;
	}

	private void buildNextBlockWithSkips() {
			
		/*
		 * 		tempSkipPos = mergeSkips(tempSkipPos);
				int temp = flushSkipsIdxPos+1;
				if (temp<flushSkipsIdxLimit) {
								
					//TODO: move first block ? 
					//System.err.println("first block "+(tempSkipPos - position)+
					// " first skip "+( flushSkips[flushSkipsIdxPos+1]-tempSkipPos ));									
				}
		 */
		
		final int endOfData = limit;
		int sourceOffset = position;
		int targetOffset = position;
	    int reqLength = BLOCK_SIZE;	
	    
	    //do not change this value after this point we are committed to this location.
	    nextBlockOffset = targetOffset;
		
		int sourceStop = flushSkips[flushSkipsIdxPos];
				
		int localLastValid = computeLocalLastValid(flushSkipsIdxLimit);
		
		//flush in parts that avoid the skip pos
		while (flushSkipsIdxPos < localLastValid && //we still have skips
				sourceStop < endOfData              //skip stops before goal
				) { 
			
			sourceStop = mergeSkips(sourceStop);
			
			int flushRequest = sourceStop - sourceOffset;
			
			if (flushRequest >= reqLength) {
				finishBlockAndLeaveRemaining(sourceOffset, targetOffset, reqLength);
				return;
			} else {
				//keep accumulating
				if (targetOffset != sourceOffset) {			
					System.arraycopy(buffer, sourceOffset, buffer, targetOffset, flushRequest);
				}
				//increment by byte written to build a contiguous block
				targetOffset += flushRequest;
				//decrement by bytes written to track how many more before block boundary.
				reqLength -= flushRequest;

			}						
			
			//did flush up to skip so set rollingPos to after skip
			sourceOffset = flushSkips[++flushSkipsIdxPos]; //new position in second part of flush skips
			sourceStop = flushSkips[++flushSkipsIdxPos]; //beginning of new skip.

		} 

		//reset to zero to save space if possible
		if (flushSkipsIdxPos==flushSkipsIdxLimit) {
			flushSkipsIdxPos=flushSkipsIdxLimit=0;
			
			int flushRequest = endOfData - sourceOffset;
			
			if (flushRequest >= reqLength) {
				finishBlockAndLeaveRemaining(sourceOffset, targetOffset, reqLength);
				return;
			} else {
				//keep accumulating
				if (sourceOffset!=targetOffset) {					
					System.arraycopy(buffer, sourceOffset, buffer, targetOffset, flushRequest);
				}
				targetOffset += flushRequest;
				reqLength -= flushRequest;
				sourceOffset += flushRequest;
			}
		} 
		nextBlockSize = BLOCK_SIZE - reqLength;
		pendingPosition = sourceOffset;
				
	}

	private int mergeSkips(int tempSkipPos) {
		//if the skip is zero bytes just flush it all together
		int temp = flushSkipsIdxPos+1;
		
		if (temp<flushSkipsIdxLimit && flushSkips[temp]==tempSkipPos) {
				++flushSkipsIdxPos;
				tempSkipPos = flushSkips[++flushSkipsIdxPos];
		}
		return tempSkipPos;
	}

	private void finishBlockAndLeaveRemaining(int sourceOffset, int targetOffset, int reqLength) {
		//more to flush than we need
		if (sourceOffset!=targetOffset) {
			System.arraycopy(buffer, sourceOffset, buffer, targetOffset, reqLength);
		}
		nextBlockSize = BLOCK_SIZE;
		pendingPosition = sourceOffset+reqLength;
	}

	private int computeLocalLastValid(int skipsIdxLimit) {
		int localLastValid;
		if (skipsIdxLimit<flushSkips.length-2) {
			localLastValid = skipsIdxLimit;
		} else {
			localLastValid = flushSkips.length-2;
		}
		return localLastValid;
	}

	public int nextOffset() {
	    if (nextBlockSize<0) {
	    	throw new FASTException();
	    }
		int nextOffset = nextBlockOffset;
		
		totalWritten += nextBlockSize;
		
		position = pendingPosition;
		nextBlockSize = -1;
		nextBlockOffset = -1;
		pendingPosition = 0;
		
		if (position == limit &&
		    0 == safetyStackDepth &&
			0 == flushSkipsIdxLimit) { 
			position = limit = 0;
		}	
		
		return nextOffset;
	}
	
	
    
    public final void flush () { //flush all 
    	output.flush();
    }
    
    protected int computeFlushToIndex() {
		int flushTo = limit;
		if (safetyStackDepth>0) {
			//only need to check first entry on stack the rest are larger values
			int safetyLimit = safetyStackPosition[0]-1;//still modifying this position but previous is ready to go.
			if (safetyLimit < flushTo) {
				flushTo = safetyLimit;
			}		
		}
		return flushTo;
	}


	//this requires the null adjusted length to be written first.
	public final void writeByteArrayData(byte[] data) {
		final int len = data.length;
		if (limit>buffer.length-len) {
			output.flush();
		}
		System.arraycopy(data, 0, buffer, limit, len);
		limit += len;
		
	}
		
	public final void writeNull() {
		if (limit>=buffer.length) {
			output.flush();
		}
		buffer[limit++] = (byte)0x80;
	}
	
	public final void writeSignedLongNullable(long value) {

		if (value >= 0) {
			writeSignedLongPos(value+1);
		} else {

			if ((value << 1) == 0) {
				if (limit > buffer.length - 10) {
					output.flush();
				}
				// encode the most negative possible number
				buffer[limit++] = (byte) (0x7F); // 8... .... .... ....
				buffer[limit++] = (byte) (0x00); // 7F.. .... .... ....
				buffer[limit++] = (byte) (0x00); // . FE .... .... ....
				buffer[limit++] = (byte) (0x00); // ...1 FC.. .... ....
				buffer[limit++] = (byte) (0x00); // .... .3F8 .... ....
				buffer[limit++] = (byte) (0x00); // .... ...7 F... ....
				buffer[limit++] = (byte) (0x00); // .... .... .FE. ....
				buffer[limit++] = (byte) (0x00); // .... .... ...1 FC..
				buffer[limit++] = (byte) (0x00); // .... .... .... 3F8.
				buffer[limit++] = (byte) (0x80); // .... .... .... ..7f
			} else {
				writeSignedLongNeg(value);
			}
		}
	}
	
	public final void writeSignedLong(long value) {

		if (value >= 0) {
			writeSignedLongPos(value);
		} else {

			if ((value << 1) == 0) {
				if (limit > buffer.length - 10) {
					output.flush();
				}
				// encode the most negative possible number
				buffer[limit++] = (byte) (0x7F); // 8... .... .... ....
				buffer[limit++] = (byte) (0x00); // 7F.. .... .... ....
				buffer[limit++] = (byte) (0x00); // . FE .... .... ....
				buffer[limit++] = (byte) (0x00); // ...1 FC.. .... ....
				buffer[limit++] = (byte) (0x00); // .... .3F8 .... ....
				buffer[limit++] = (byte) (0x00); // .... ...7 F... ....
				buffer[limit++] = (byte) (0x00); // .... .... .FE. ....
				buffer[limit++] = (byte) (0x00); // .... .... ...1 FC..
				buffer[limit++] = (byte) (0x00); // .... .... .... 3F8.
				buffer[limit++] = (byte) (0x80); // .... .... .... ..7f
			} else {
				writeSignedLongNeg(value);
			}
		}
	}

	private final void writeSignedLongNeg(long value) {
		// using absolute value avoids tricky word length issues
		long absv = -value;
		
		if (absv <= 0x0000000000000040l) {
			if (buffer.length - limit < 1) {
				output.flush();
			}
		} else {
			if (absv <= 0x0000000000002000l) {
				if (buffer.length - limit < 2) {
					output.flush();
				}
			} else {

				if (absv <= 0x0000000000100000l) {
					if (buffer.length - limit < 3) {
						output.flush();
					}
				} else {

					if (absv <= 0x0000000008000000l) {
						if (buffer.length - limit < 4) {
							output.flush();
						}
					} else {
						if (absv <= 0x0000000400000000l) {
					
							if (buffer.length - limit < 5) {
								output.flush();
							}
						} else {
							writeSignedLongNegSlow(absv, value);
							return;
						}
						buffer[limit++] = (byte)(((value >> 28) & 0x7F));
					}
					buffer[limit++] = (byte) (((value >> 21) & 0x7F));
				}
				buffer[limit++] = (byte) (((value >> 14) & 0x7F));
			}
			buffer[limit++] = (byte) (((value >> 7) & 0x7F));
		}
		buffer[limit++] = (byte) (((value & 0x7F) | 0x80));

	}

	private final void writeSignedLongNegSlow(long absv, long value) {
		if (absv <= 0x0000020000000000l) {
			if (buffer.length - limit < 6) {
				output.flush();
			}
		} else {
			writeSignedLongNegSlow2(absv, value);
		}

		// used by all
		buffer[limit++] = (byte) (((value >> 35) & 0x7F));
		buffer[limit++] = (byte) (((value >> 28) & 0x7F));
		buffer[limit++] = (byte) (((value >> 21) & 0x7F));
		buffer[limit++] = (byte) (((value >> 14) & 0x7F));
		buffer[limit++] = (byte) (((value >> 7) & 0x7F));
		buffer[limit++] = (byte) (((value & 0x7F) | 0x80));
	}

	private void writeSignedLongNegSlow2(long absv, long value) {
		if (absv <= 0x0001000000000000l) {
			if (buffer.length - limit < 7) {
				output.flush();
			}
		} else {
			if (absv <= 0x0080000000000000l) {
				if (buffer.length - limit < 8) {
					output.flush();
				}
			} else {
				if (buffer.length - limit < 9) {
					output.flush();
				}
				buffer[limit++] = (byte) (((value >> 56) & 0x7F));
			}
			buffer[limit++] = (byte) (((value >> 49) & 0x7F));
		}
		buffer[limit++] = (byte) (((value >> 42) & 0x7F));
	}

	private final void writeSignedLongPos(long value) {
		
		if (value < 0x0000000000000040l) {
			if (buffer.length - limit < 1) {
				output.flush();
			}
		} else {
			if (value < 0x0000000000002000l) {
				if (buffer.length - limit < 2) {
					output.flush();
				}
			} else {

				if (value < 0x0000000000100000l) {
					if (buffer.length - limit < 3) {
						output.flush();
					}
				} else {

					if (value < 0x0000000008000000l) {
						if (buffer.length - limit < 4) {
							output.flush();
						}
					} else {
						if (value < 0x0000000400000000l) {
					
							if (buffer.length - limit < 5) {
								output.flush();
							}
						} else {
							writeSignedLongPosSlow(value);
							return;
						}
						buffer[limit++] = (byte)(((value >> 28) & 0x7F));
					}
					buffer[limit++] = (byte) (((value >> 21) & 0x7F));
				}
				buffer[limit++] = (byte) (((value >> 14) & 0x7F));
			}
			buffer[limit++] = (byte) (((value >> 7) & 0x7F));
		}
		buffer[limit++] = (byte) (((value & 0x7F) | 0x80));
	}

	private final void writeSignedLongPosSlow(long value) {
		if (value < 0x0000020000000000l) {
			if (buffer.length - limit < 6) {
				output.flush();
			}
		} else {
			if (value < 0x0001000000000000l) {
				if (buffer.length - limit < 7) {
					output.flush();
				}
			} else {
				if (value < 0x0080000000000000l) {
					if (buffer.length - limit < 8) {
						output.flush();
					}
				} else {
					if (value < 0x4000000000000000l) {
						if (buffer.length - limit < 9) {
							output.flush();
						}
					} else {
						if (buffer.length - limit < 10) {
							output.flush();
						}
						buffer[limit++] = (byte) (((value >> 63) & 0x7F));
					}
					buffer[limit++] = (byte) (((value >> 56) & 0x7F));
				}
				buffer[limit++] = (byte) (((value >> 49) & 0x7F));
			}
			buffer[limit++] = (byte) (((value >> 42) & 0x7F));
		}

		// used by all
		buffer[limit++] = (byte) (((value >> 35) & 0x7F));
		buffer[limit++] = (byte) (((value >> 28) & 0x7F));
		buffer[limit++] = (byte) (((value >> 21) & 0x7F));
		buffer[limit++] = (byte) (((value >> 14) & 0x7F));
		buffer[limit++] = (byte) (((value >> 7) & 0x7F));
		buffer[limit++] = (byte) (((value & 0x7F) | 0x80));
	}

	public final void writeUnsignedLongNullable(long value) {
		writeUnsignedLong(value+1);
	}
	
	public final void writeUnsignedLong(long value) {

			if (value < 0x0000000000000080l) {
				if (buffer.length - limit < 1) {
					output.flush();
				}
			} else {
				if (value < 0x0000000000004000l) {
					if (buffer.length - limit < 2) {
						output.flush();
					}
				} else {

					if (value < 0x0000000000200000l) {
						if (buffer.length - limit < 3) {
							output.flush();
						}
					} else {

						if (value < 0x0000000010000000l) {
							if (buffer.length - limit < 4) {
								output.flush();
							}
						} else {
							if (value < 0x0000000800000000l) {
						
								if (buffer.length - limit < 5) {
									output.flush();
								}
							} else {
								writeUnsignedLongSlow(value);
								return;
							}
							buffer[limit++] = (byte)(((value >> 28) & 0x7F));
						}
						buffer[limit++] = (byte) (((value >> 21) & 0x7F));
					}
					buffer[limit++] = (byte) (((value >> 14) & 0x7F));
				}
				buffer[limit++] = (byte) (((value >> 7) & 0x7F));
			}
			buffer[limit++] = (byte) (((value & 0x7F) | 0x80));
	}

	private final void writeUnsignedLongSlow(long value) {
		if (value < 0x0000040000000000l) {
			if (buffer.length - limit < 6) {
				output.flush();
			}
		} else {
			writeUnsignedLongSlow2(value);
		}

		// used by all
		buffer[limit++] = (byte) (((value >> 35) & 0x7F));
		buffer[limit++] = (byte) (((value >> 28) & 0x7F));
		buffer[limit++] = (byte) (((value >> 21) & 0x7F));
		buffer[limit++] = (byte) (((value >> 14) & 0x7F));
		buffer[limit++] = (byte) (((value >> 7) & 0x7F));
		buffer[limit++] = (byte) (((value & 0x7F) | 0x80));

	}

	private void writeUnsignedLongSlow2(long value) {
		if (value < 0x0002000000000000l) {
			if (buffer.length - limit < 7) {
				output.flush();
			}
		} else {
			if (value < 0x0100000000000000l) {
				if (buffer.length - limit < 8) {
					output.flush();
				}
			} else {
				if (value < 0x8000000000000000l) {
					if (buffer.length - limit < 9) {
						output.flush();
					}
				} else {
					if (buffer.length - limit < 10) {
						output.flush();
					}
					buffer[limit++] = (byte) (((value >> 63) & 0x7F));
				}
				buffer[limit++] = (byte) (((value >> 56) & 0x7F));
			}
			buffer[limit++] = (byte) (((value >> 49) & 0x7F));
		}
		buffer[limit++] = (byte) (((value >> 42) & 0x7F));
	}
	
	
	public final void writeSignedIntegerNullable(int value) {
		if (value >= 0) { 
			writeSignedIntegerPos(value+1);
		} else {
			if ((value << 1) == 0) {
				if (limit > buffer.length - 5) {
					output.flush();
				}
				// encode the most negative possible number
				buffer[limit++] = (byte) (0x7F); // .... ...7 F... ....
				buffer[limit++] = (byte) (0x00); // .... .... .FE. ....
				buffer[limit++] = (byte) (0x00); // .... .... ...1 FC..
				buffer[limit++] = (byte) (0x00); // .... .... .... 3F8.
				buffer[limit++] = (byte) (0x80); // .... .... .... ..7f
			} else {
				writeSignedIntegerNeg(value);
			}
		}
	}
	
	public final void writeSignedInteger(int value) {
		if (value >= 0) { 
			writeSignedIntegerPos(value);
		} else {
			if ((value << 1) == 0) {
				if (limit > buffer.length - 5) {
					output.flush();
				}
				// encode the most negative possible number
				buffer[limit++] = (byte) (0x7F); // .... ...7 F... ....
				buffer[limit++] = (byte) (0x00); // .... .... .FE. ....
				buffer[limit++] = (byte) (0x00); // .... .... ...1 FC..
				buffer[limit++] = (byte) (0x00); // .... .... .... 3F8.
				buffer[limit++] = (byte) (0x80); // .... .... .... ..7f
			} else {
				writeSignedIntegerNeg(value);
			}
		}
	}

	private void writeSignedIntegerNeg(int value) {
	    // using absolute value avoids tricky word length issues
	    int absv = -value;
	    
	    
		if (absv <= 0x00000040) {
			if (buffer.length - limit < 1) {
				output.flush();
			}
		} else {
			if (absv <= 0x00002000) {
				if (buffer.length - limit < 2) {
					output.flush();
				}
			} else {
				if (absv <= 0x00100000) {
					if (buffer.length - limit < 3) {
						output.flush();
					}
				} else {
					if (absv <= 0x08000000) {
						if (buffer.length - limit < 4) {
							output.flush();
						}
					} else {
						if (buffer.length - limit < 5) {
							output.flush();
						}
						buffer[limit++] = (byte)(((value >> 28) & 0x7F));
					}
					buffer[limit++] = (byte) (((value >> 21) & 0x7F));
				}
				buffer[limit++] = (byte) (((value >> 14) & 0x7F));
			}
			buffer[limit++] = (byte) (((value >> 7) & 0x7F));
		}
		buffer[limit++] = (byte) (((value & 0x7F) | 0x80));
	    
	    
	}

	private void writeSignedIntegerPos(int value) {
		
		if (value < 0x00000040) {
			if (buffer.length - limit < 1) {
				output.flush();
			}
		} else {
			if (value < 0x00002000) {
				if (buffer.length - limit < 2) {
					output.flush();
				}
			} else {
				if (value < 0x00100000) {
					if (buffer.length - limit < 3) {
						output.flush();
					}
				} else {
					if (value < 0x08000000) {
						if (buffer.length - limit < 4) {
							output.flush();
						}
					} else {
						if (buffer.length - limit < 5) {
							output.flush();
						}
						buffer[limit++] = (byte)(((value >> 28) & 0x7F));
					}
					buffer[limit++] = (byte) (((value >> 21) & 0x7F));
				}
				buffer[limit++] = (byte) (((value >> 14) & 0x7F));
			}
			buffer[limit++] = (byte) (((value >> 7) & 0x7F));
		}
		buffer[limit++] = (byte) (((value & 0x7F) | 0x80));
				
	}
	
	public final void writeUnsignedIntegerNullable(int value) {
		writeUnsignedInteger(value+1);
	}
	
	public final void writeUnsignedInteger(int value) {
		
		if (value < 0x00000080) {
			if (buffer.length - limit < 1) {
				output.flush();
			}
		} else {
			if (value < 0x00004000) {
				if (buffer.length - limit < 2) {
					output.flush();
				}
			} else {
				if (value < 0x00200000) {
					if (buffer.length - limit < 3) {
						output.flush();
					}
				} else {
					if (value < 0x10000000) {
						if (buffer.length - limit < 4) {
							output.flush();
						}
					} else {
						if (buffer.length - limit < 5) {
							output.flush();
						}
						buffer[limit++] = (byte)(((value >> 28) & 0x7F));
					}
					buffer[limit++] = (byte) (((value >> 21) & 0x7F));
				}
				buffer[limit++] = (byte) (((value >> 14) & 0x7F));
			}
			buffer[limit++] = (byte) (((value >> 7) & 0x7F));
		}
		buffer[limit++] = (byte) (((value & 0x7F) | 0x80));
	}


	///////////////////////////////////
	//New PMAP writer implementation
	///////////////////////////////////
	
	//called only at the beginning of a group.
	public final void openPMap(int maxBytes) {
		
		if (limit > buffer.length - maxBytes) {
			output.flush();
		}
		
		//save the current partial byte.
		//always save because pop will always load
		if (safetyStackDepth>0) {			
			int s = safetyStackDepth-1;
			assert(s>=0) : "Must call pushPMap(maxBytes) before attempting to write bits to it";		
			
			//final byte to be saved into the feed.
			if (0 != (buffer[safetyStackPosition[s]++] = pMapByteAccum)) {	
				//set the last known non zero bit so we can avoid scanning for it. 
				flushSkips[safetyStackFlushIdx[s]] = safetyStackPosition[s];// one has been added for exclusive use of range
			}				
			safetyStackTemp[s] = pMapIdxWorking;
		} 		
		safetyStackPosition[safetyStackDepth] = limit;
		safetyStackFlushIdx[safetyStackDepth++] = flushSkipsIdxLimit;
		flushSkips[flushSkipsIdxLimit++] = limit+1;//default minimum size for present PMap
		flushSkips[flushSkipsIdxLimit++] = (limit += maxBytes);//this will remain as the fixed limit					
		
		//reset so we can start accumulating bits in the new pmap.
		pMapIdxWorking = 7;
		pMapByteAccum = 0;			
	}
	
	//called only at the end of a group.
	public final void closePMap() {
		/////
		//the PMap is ready for writing.
		//bit writes will go to previous bitmap location
		/////
		//push open writes
	   	int s = --safetyStackDepth;
		assert(s>=0) : "Must call pushPMap(maxBytes) before attempting to write bits to it";
						
		//final byte to be saved into the feed.
		if (0 != (buffer[safetyStackPosition[s]++] = pMapByteAccum)) {	
			//set the last known non zero bit so we can avoid scanning for it. 
			flushSkips[safetyStackFlushIdx[s]] = safetyStackPosition[s];// one has been added for exclusive use of range
		}	
		
		buffer[flushSkips[safetyStackFlushIdx[safetyStackDepth]] - 1] |= 0x80;//must set stop bit now that we know where pmap stops.
				
		//restore the old working bits if there is a previous pmap.
		if (safetyStackDepth>0) {			
			popWorkingBits(safetyStackDepth-1);
		}
		
		//ensure low-latency for groups, or 
    	//if we can reset the safety stack and we have one block ready go ahead and flush
    	if (minimizeLatency || (0==safetyStackDepth && (limit-position)>(BLOCK_SIZE_LAZY) )) { //one block and a bit left over so we need bigger.
    		output.flush();
    	}
	}

	private final void popWorkingBits(int s) {
		pMapByteAccum = buffer[safetyStackPosition[s]--];
		pMapIdxWorking = safetyStackTemp[s];
	}
	
	//called by ever field that needs to set a bit either 1 or 0
	//must be fast because it is frequently called.
	public final void writePMapBit(int bit) {
		if (0 == --pMapIdxWorking) {
			int s = safetyStackDepth-1; 
			
			assert(s>=0) : "Must call pushPMap(maxBytes) before attempting to write bits to it";
					
			int local = (pMapByteAccum | bit);
			buffer[safetyStackPosition[s]++] = (byte) local;//final byte to be saved into the feed.
			safetyStackTemp[s] = pMapIdxWorking;
			
			if (0 != local) {	
				//set the last known non zero bit so we can avoid scanning for it. 
				flushSkips[safetyStackFlushIdx[s]] = safetyStackPosition[s];// one has been added for exclusive use of range
			}	
			
			pMapIdxWorking = 7;
			pMapByteAccum = 0;
			
		} else {
			pMapByteAccum |= (bit<<pMapIdxWorking); 
		}
	}

	public boolean isPMapOpen() {
		return safetyStackDepth>0;
	}
	
	public final void writeASCII(CharSequence value) {
		
		int length = value.length();
		if (0==length) {
			encodeZeroLengthASCII();
			return;
		} else	if (limit>buffer.length-length) {
			//if it was not zero and was too long flush
			output.flush();
		}
		int c = 0;
		while (--length>0) {
			buffer[limit++] = (byte)value.charAt(c++);
		}
		buffer[limit++] = (byte)(0x80|value.charAt(c));
		
	}

	private void encodeZeroLengthASCII() {
		if (limit>buffer.length-2) {
			output.flush();
		}
		buffer[limit++] = (byte)0;
		buffer[limit++] = (byte)0x80;
	}

	public void writeASCII(char[] value, int offset, int length) {

		if (0==length) {
			encodeZeroLengthASCII();
			return;
		} else	if (limit>buffer.length-length) {
			//if it was not zero and was too long flush
			output.flush();
		}
		while (--length>0) {
			buffer[limit++] = (byte)value[offset++];
		}
		buffer[limit++] = (byte)(0x80|value[offset]);
	}

	public void writeUTF(CharSequence value) {
		int len = value.length();
		int c = 0;
		while (c<len) {
			encodeSingleChar(value.charAt(c++));
		}		
	}

	public void writeUTF(char[] value, int offset, int length) {
		while (--length>=0) {
			encodeSingleChar(value[offset++]);
		}
	}

	private void encodeSingleChar(int c) {
		
		if (c<=0x007F) {
			//code point 7
			if (limit>buffer.length-1) {
				output.flush();
			}
			buffer[limit++] = (byte)c;
		} else {
			if (c<=0x07FF) {
				//code point 11
				if (limit>buffer.length-2) {
					output.flush();
				}
				buffer[limit++] = (byte)(0xC0|((c>>6)&0x1F));
			} else {
				if (c<=0xFFFF) {
					//code point 16
					if (limit>buffer.length-3) {
						output.flush();
					}
					buffer[limit++] = (byte)(0xE0|((c>>12)&0x0F));
				} else {
					encodeSingleCharSlow(c);
				}
				buffer[limit++] = (byte)(0x80 |((c>>6) &0x3F));
			}
			buffer[limit++] = (byte)(0x80 |((c)   &0x3F));
		}
	}

	protected void encodeSingleCharSlow(int c) {
		if (c<0x1FFFFF) {
			//code point 21
			if (limit>buffer.length-4) {
				output.flush();
			}
			buffer[limit++] = (byte)(0xF0|((c>>18)&0x07));
		} else {
			if (c<0x3FFFFFF) {
				//code point 26
				if (limit>buffer.length-5) {
					output.flush();
				}
				buffer[limit++] = (byte)(0xF8|((c>>24)&0x03));
			} else {
				if (c<0x7FFFFFFF) {
					//code point 31
					if (limit>buffer.length-6) {
						output.flush();
					}
					buffer[limit++] = (byte)(0xFC|((c>>30)&0x01));
				} else {
					throw new UnsupportedOperationException("can not encode char with value: "+c);
				}
				buffer[limit++] = (byte)(0x80 |((c>>24) &0x3F));
			}
			buffer[limit++] = (byte)(0x80 |((c>>18) &0x3F));
		}						
		buffer[limit++] = (byte)(0x80 |((c>>12) &0x3F));
	}
	
}
