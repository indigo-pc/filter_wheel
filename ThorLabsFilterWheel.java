package com.ioi.utilities;

import java.util.HashMap;
import java.util.Set;

import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;
import jssc.SerialPortTimeoutException;

/**
 * Class models ThorLabs FW212CNEB Filter Wheel. Default configuration is 12 filter locations with fast rotation speed.
 * Based on some early code from His Excellency The Venerable Trevor Baker.
 * 
 * @author Phillip Curtsmith
 * @since Spring 2022
 *
 */
public class ThorLabsFilterWheel {
	
	private SerialPort serialPort;
    private String response = null;
    private final String CARRIAGE_RETURN = "\r";
    private final String CALIBRATION_DIRECTORY = "C:\\IOI\\bin\\defaultcalibration";
    private final HashMap< String, Integer > OD_TO_POSITION = new HashMap< String, Integer >();
        
    /**
     * Close serial port.
     * @return True if successful, else, false.
     * @throws SerialPortException
     */
	public boolean closePort() throws SerialPortException {
        return serialPort.closePort();
	}

	/**
	 * Recover the mapping of the OD filter to a position on the filter wheel.
	 * @param Optical density filter
	 * @return Filter position [0, 12]
	 */
    public int convertOdToPos( String OD ) {
        return OD_TO_POSITION.get( OD );
    }

    /**
     * Check if serial port is open.
     * @return True if open, else, false.
     */
    public boolean isPortOpen() {
        return serialPort.isOpened();
    }
    
    /**
     * Configure default values for filter wheel count (12) and speed mode (fast).
     * @throws SerialPortException
     * @throws InterruptedException
     * @throws SerialPortTimeoutException
     */
    public void configureDefault() throws SerialPortException, InterruptedException, SerialPortTimeoutException {
        setPositionCount( 12 );
        setSpeedMode( "fast" );
    }
    
    /**
     * Save any setting established by user, e.g., #setSpeedMode.
     * @throws SerialPortException
     * @throws InterruptedException
     * @throws SerialPortTimeoutException
     */
    public void saveSettings() throws SerialPortException, InterruptedException, SerialPortTimeoutException {
    	sendCommand( "save" );
    }
    
    /**
     * Recover the sensor mode from the filter wheel.
     * @return 0 if sensor off, 1 if sensor on.
     * @throws SerialPortException
     * @throws InterruptedException
     * @throws SerialPortTimeoutException
     */
    public String getSensorMode() throws SerialPortException, InterruptedException, SerialPortTimeoutException {
        return sendCommand( "sensors?" );
    }
    
    /**
     * Set sensor mode for the filter wheel.
     * @param "on" or "off".
     * @throws SerialPortException
     * @throws InterruptedException
     * @throws SerialPortTimeoutException
     */
    public void setSensorMode( String sensorMode ) throws SerialPortException, InterruptedException, SerialPortTimeoutException {
    	if ( sensorMode.equals( "on" ) ) {
    		sendCommand( "speed=1" );
    		return;
    	}
    	if ( sensorMode.equals( "off" ) ) {
    		sendCommand( "speed=0" );
    		return;
    	}
    	this.closePort();
    	throw new UnsupportedOperationException( "Unsupported sensor mode specified in ThorLabsFilterWheel#setSensorMode" );
    }
    
    /**
     * Recover speed mode setting for filter wheel.
     * @return 0 if slow, 1 if fast.
     * @throws SerialPortException
     * @throws InterruptedException
     * @throws SerialPortTimeoutException
     */
    public String getSpeedMode() throws SerialPortException, InterruptedException, SerialPortTimeoutException {
        return sendCommand( "speed?" );
    }
    
    /**
     * Set speed mode for filter wheel.
     * @param "fast" or "slow"
     * @throws SerialPortException
     * @throws InterruptedException
     * @throws SerialPortTimeoutException
     */
    public void setSpeedMode( String speedMode ) throws SerialPortException, InterruptedException, SerialPortTimeoutException {
    	if ( speedMode.equals( "slow" ) ) {
    		sendCommand( "speed=0" );
    		return;
    	}
    	if ( speedMode.equals( "fast" ) ) {
    		sendCommand( "speed=1" );
    		return;
    	}
    	this.closePort();
    	throw new UnsupportedOperationException( "Unsupported speed mode specified in ThorLabsFilterWheel#setSpeedMode" );
    }
    
    /**
     * Recover trigger mode for filter wheel.
     * @return 0 if trigger input, 1 if trigger output.
     * @throws SerialPortException
     * @throws InterruptedException
     * @throws SerialPortTimeoutException
     */
    public String getTriggerMode() throws SerialPortException, InterruptedException, SerialPortTimeoutException {
        return sendCommand( "trig?" );
    }
    
    /**
     * Set trigger mode for filter wheel.
     * @param "input" or "output".
     * @throws SerialPortException
     * @throws InterruptedException
     * @throws SerialPortTimeoutException
     */
    public void setTriggerMode( String inputMode ) throws SerialPortException, InterruptedException, SerialPortTimeoutException {
    	if ( inputMode.equals( "input" ) ) {
    		sendCommand( "trig=0" );
    		return;
    	}
    	if ( inputMode.equals( "output" ) ) {
    		sendCommand( "trig=1" );
    		return;
    	}
    	this.closePort();
    	throw new UnsupportedOperationException( "Unsupported trigger mode specified in ThorLabsFilterWheel#setTriggerMode" );
    }
    
    /**
     * Recover the number of filter positions for this wheel.
     * @return Position count, 6 or 12.
     * @throws SerialPortException
     * @throws InterruptedException
     * @throws SerialPortTimeoutException
     */
    public String getPositionCount() throws SerialPortException, InterruptedException, SerialPortTimeoutException {
        return sendCommand( "pcount?" );
    }
    
    /**
     * Set number of positions available for this filter wheel. 6 or 12 only.
     * @param 6 or 12.
     * @throws SerialPortException
     * @throws InterruptedException
     */
    public void setPositionCount( int positionCount ) throws SerialPortException, InterruptedException {
    	if ( ! ( positionCount == 6 || positionCount == 12 ) ) {
    		this.closePort();
    		throw new UnsupportedOperationException( "Invalid filter wheel position count!" );
    	}
        sendCommand( "pcount=" + positionCount );
    }
    
    /**
     * Recover the current position for the filter wheel, [1 12]
     * @return Filter wheel position.
     * @throws SerialPortException
     * @throws InterruptedException
     * @throws SerialPortTimeoutException
     */
    public String getPosition() throws SerialPortException, InterruptedException, SerialPortTimeoutException {
        return sendCommand( "pos?" );
    }
    
    /**
     * Set a filter wheel position, [1 12]
     * @param Filter wheel position, [1 12]
     * @throws SerialPortException
     * @throws InterruptedException
     */
    public void setPosition( int position ) throws SerialPortException, InterruptedException {
    	if ( position < 1 || position > 12 ) {
    		this.closePort();
    		throw new UnsupportedOperationException( "Invalid filter wheel position!" );
    	}
        sendCommand( "pos=" + position );
    }
    
    /**
     * Get filter wheel model and firmware version.
     * @return Filter wheel model and firmware version.
     * @throws SerialPortException
     * @throws InterruptedException
     */
    public String getID() throws SerialPortException, InterruptedException {
        return sendCommand( "*idn?" );
    }
    
    /**
     * General method to send command over serial. All commands preceded with transmit and receive purge.
     * @param Serial command.
     * @return Response from port.
     * @throws SerialPortException
     * @throws InterruptedException
     */
    public String sendCommand( String s ) throws SerialPortException, InterruptedException {
		response = null;
		serialPort.purgePort(SerialPort.PURGE_RXCLEAR);
		serialPort.purgePort(SerialPort.PURGE_TXCLEAR);
		serialPort.writeString( s + CARRIAGE_RETURN);
        do {
        	Thread.sleep( 5 );
        } while ( response == null );
		return response; 	
	}
	
    /**
     * Recover location of calibration files for spectrometer.
     * @param Optical density of a filter
     * @return Full path, including file name, for a calibration file.
     */
    public String getCalibrationFromOD(String od) {
    	return CALIBRATION_DIRECTORY + od + ".calibration";
    }
    
    /**
     * Build a ThorLabsFilterWheel object at a given port.
     * @param Com port.
     * @throws SerialPortException
     * @throws InterruptedException
     * @throws SerialPortTimeoutException
     */
    public ThorLabsFilterWheel( String port ) throws SerialPortException, InterruptedException, SerialPortTimeoutException {
        initializeHashMap();
        serialPort = new SerialPort( port );
        serialPort.openPort();
        serialPort.setParams(		SerialPort.BAUDRATE_115200,
                					SerialPort.DATABITS_8,
                					SerialPort.STOPBITS_1,
                					SerialPort.PARITY_NONE	);
        serialPort.addEventListener( new PortReader() );
        configureDefault();
    }
    
    /**
     * Initialize values to hash map, relating OD filter values to filter wheel locations.
     */
    private void initializeHashMap() {
        OD_TO_POSITION.put( "0"  , 6	);
        OD_TO_POSITION.put( "0.1", 7	);
        OD_TO_POSITION.put( "0.2", 8	);
        OD_TO_POSITION.put( "0.3", 9	);
        OD_TO_POSITION.put( "0.4", 10	);
        OD_TO_POSITION.put( "0.5", 11	);
        OD_TO_POSITION.put( "0.6", 12	);
        OD_TO_POSITION.put( "1.0", 1	);
        OD_TO_POSITION.put( "1.3", 2	);
        OD_TO_POSITION.put( "2.0", 3	);
        // no calibrations for these two filters
//        OD_TO_POSITION.put( "3.0", 4	); // doesn't exist?
//        OD_TO_POSITION.put( "4.0", 5	); // doesn't exist?
    }
    
    /**
     * Recover set of filters, defined by OD.
     * @return set of filter ODs
     */
    public Set<String> getFilterODs() {
    	return OD_TO_POSITION.keySet();
    }
    
    /**
     * Port listener. Ensures that Java waits for serial prompt to return when waiting on reply or issuing another command.
     * @see https://arduino.stackexchange.com/questions/3755/how-to-use-readline-from-jssc
     */
	protected class PortReader implements SerialPortEventListener {
		StringBuilder message = new StringBuilder();
		@Override
		public void serialEvent(SerialPortEvent event) {
			if ( event.isRXCHAR() && event.getEventValue() > 0 ) {
				try {
					byte buffer[] = serialPort.readBytes();
					for (byte b : buffer) {
						// Per manufacturer specification, all responses from filter wheel terminate with '>'
						if ( b == '>' ) {
							response = message.toString().trim();
							message.setLength(0);
						} else {
							message.append( (char) b );
						}
					}
				} catch (SerialPortException ex) { ex.printStackTrace(); }
			}
		}
	}
	
	/**
	 * Testing only.
	 * @param args
	 * @throws SerialPortTimeoutException 
	 * @throws InterruptedException 
	 * @throws SerialPortException 
	 */
	public static void main(String[] args) throws SerialPortException, InterruptedException, SerialPortTimeoutException {
		ThorLabsFilterWheel fw = new ThorLabsFilterWheel( "/dev/cu.usbserial-1130" );
		System.out.println( fw.getID() );
		System.out.println( fw.getSensorMode() );
		System.out.println( fw.getSpeedMode() );
		System.out.println( fw.getTriggerMode() );
		System.out.println( fw.getPosition() );
		System.out.println( fw.getPositionCount() );
		for ( int i = 1; i < 13; i++ ) {
			System.out.println( "Filter set: " + i );
			fw.setPosition( i );
			Thread.sleep( 3000 );
			System.out.println( "Filter read: " + fw.getPosition() );
		}
		fw.closePort();
	}

}
