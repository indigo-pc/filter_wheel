package com.ioi.utilities;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import com.ioi.gui.Controller;
import com.oceanoptics.omnidriver.api.wrapper.Wrapper;
import com.oceanoptics.omnidriver.spectrometer.Coefficients;
import com.oceanoptics.spam.advancedprocessing.AdvancedAbsoluteIrradiance;
import com.oceanoptics.spam.advancedprocessing.AdvancedPeakFinding;
import com.oceanoptics.spam.advancedprocessing.AdvancedPhotometrics;
import com.oceanoptics.spam.advancedprocessing.NoPeakFoundException;
import com.oceanoptics.spam.arraymath.ArrayMath;
import com.oceanoptics.spam.numericalmethods.IntegrationMethod;
import com.oceanoptics.spam.numericalmethods.NumericalMethods;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import jssc.SerialPortException;
import jssc.SerialPortTimeoutException;

/**
 * Class models functions for USB Ocean Insight spectrometers. Multi-channel spectrometers are not supported. 
 * Requires OceanInsight's Omnidriver and SPAM jar. 
 * @author Phillip Curtsmith
 * @since Spring 2022
 *
 */
public class OceanOpticsSpectrometer {
	
	// Boolean determining if API functions can be called because OceanOptics apparently has no software engineers to handle major system architecture problems
	boolean isInCompatabilityMode = false;
	
	// Objects from Ocean Optics APIs
	private Wrapper spectrometer;
	private NumericalMethods maths = new NumericalMethods();
	
	// Local data structures
	private HashMap< String,Integer > spectrometerMap = new HashMap< String,Integer >();
	private HashMap< String,double[] > darkSpectrumMap = new HashMap< String, double[] >();
	private HashMap< String,double[] > calibrationSpectrumMap = new HashMap< String, double[] >();
	private Double[] nullWavelengths = {-1.00};
	private double[] peakWavelengths;
	
	// Data structures associated with spectral measurements.
	private double collectionArea = 2.85026; // e.g., area of integrating sphere aperture (inlet) in cm^2
	private double[] irradiance;
	private double[] uWPerCmSquaredPerNm;
	private double uWatts;
	private static final IntegrationMethod INTEGRATION_METHOD = IntegrationMethod.INTEGRAL_TRAPEZOID;
	private static final int MICRO_WATT_SCALAR = 1000000;
	
	// Spectrometer Calibration
	private double[] calibrationValues;
	private ArrayList<String> calibrationFileContent = new ArrayList<String>();
	private final int DATA_START_LINE = 5;
	private final int PIXEL_COUNT_LINE = 1;
    private BufferedReader br = null;
    private String fileLine;
    private String specTemp;
    private String classError;
    private String jarCalibrationDirectoryDefault = Controller.jarParentDirectory() + File.separator + "spectrometer"; 
    private String ideCalibrationDirectoryDefault = "src" + File.separator + "spectrometer";
		
	/**
	 * Refresh the list of spectrometers available on the system. Each method call in this API requires a member of this list, passed as a string.
	 * Warning: refreshing the list may cause individual devices to be shuffled in the list such that the String identifying one device becomes
	 * associated with a different underlying ID. Subsequent calls to other methods would therefore refer to the incorrect device.
	 * Hot-plugging several devices during program usage is therefore not recommended, or must be managed judiciously through the program client.
	 * Calls to this method also clear dark spectra collected for all spectrometers.
	 * @return list of spectrometers (strings)
	 */
	public ObservableList<String> refreshSpectrometerList() {
		spectrometerMap.clear();
		darkSpectrumMap.clear();
		spectrometer.closeAllSpectrometers();
		spectrometer.openAllSpectrometers();
		for ( int i = 0; i < spectrometer.getNumberOfSpectrometersFound(); i++ ) {
			specTemp = spectrometer.getName(i) + " SN:" + spectrometer.getSerialNumber(i);
			spectrometerMap.put( specTemp, i );
			darkSpectrumMap.put( specTemp, null );
			calibrationSpectrumMap.put( specTemp, null );
		}
		return FXCollections.observableArrayList( spectrometerMap.keySet() );
	}
	
	/**
	 * Establish the surface area of the outermost aperture of the integrating sphere in square centimeters.
	 * @param d : surface area in square centimeters.
	 */
	public void setCollectionArea( double d ) {
		collectionArea = d;
	}
	
	/**
	 * Call this boolean in all client-side calls to distinguish between compatible architectures (Windows and possibly Linux) and incompatible architectures (MacOS). This method is required because OceanInsight offers below average software support for their products.
	 * @return true if calling any methods would result in crash, false otherwise.
	 */
	public boolean isInCompatabilityMode() {
		if ( isInCompatabilityMode ) {
			System.err.println( "Cannot launch spectrometer function. Spectrometer in compatability mode. OceanInsight really needs to get their shit togther." );
		}
		return isInCompatabilityMode;
	}
	
	/**
	 * Build an OceanOpticsSpectrometer using default calibrations. To load system and ND filter specific calibrations, use {@link #loadCalibrationForFilter(String)}.
	 */
	public OceanOpticsSpectrometer() {
		if ( System.getProperty( "os.name" ).contains( "Mac" ) ) {
			System.err.println( "Cannot initialize OceanOpticsSpectrometer.java. Incompatible system architecture. Running in compatability mode." );
			isInCompatabilityMode = true;
			return;
		}
		spectrometer = new Wrapper();
		refreshSpectrometerList();
	}
	
	/**
	 * Find peak(s) within a spectrum. If none found, method returns an empty set. If an error occurs, method returns an array with a single value, -1.00. All values rounded to two decimal places.
	 * @param s : spectrometer detected on the system
	 * @param minimumIndicesBetweenPeaks : local maxima in this interval constitute only a single peak.
	 * @param thresholdMagnitude : magnitudes below this threshold (counts) will not be considered a peak.
	 * @return an array of wavelengths where peaks are found.
	 */
	public ObservableList<Double> getPeakWavelengths( String s, int minimumIndicesBetweenPeaks, double thresholdMagnitude ) {
		confirmDeviceDetected(s);
		try {
			peakWavelengths = AdvancedPeakFinding.getPeakWavelengths( getWavelengths(s), getSpectrum(s), minimumIndicesBetweenPeaks, thresholdMagnitude );
			for ( int i = 0; i < peakWavelengths.length; i++ ) {
				peakWavelengths[i] = Math.round(peakWavelengths[i] * 100.0) / 100.0;
			}
			return FXCollections.observableArrayList( DoubleStream.of( peakWavelengths ).boxed().collect(Collectors.toList()) ); 
		} catch ( NoPeakFoundException e ) {
			return FXCollections.observableArrayList( nullWavelengths );
		}
	}
	
	/**
	 * Find the index in the array composing spectrum data, where peaks occur. 
	 * @param s : spectrometer detected on the system
	 * @param minimumIndicesBetweenPeaks : local maxima in this interval constitute only a single peak.
	 * @param thresholdMagnitude : magnitudes below this threshold (counts) will not be considered a peak.
	 * @return and array of indices where peaks are found.
	 */
	public ObservableList<Integer> getPeakIndices( String s, int minimumIndicesBetweenPeaks, double thresholdMagnitude ) {
		confirmDeviceDetected(s);
		return FXCollections.observableArrayList( 
													IntStream.of( 
													AdvancedPeakFinding.getPeakIndices(		getSpectrum(s), 
																							minimumIndicesBetweenPeaks, 
																							thresholdMagnitude ) )
													.boxed()
													.collect( Collectors.toList() ) 		); 
	}
			
	/**
	 * Perform a trapezoidal numerical integration using a spectrum collected at the time of this method call.
	 * This method uses UNCALIBRATED data in counts/nm. For absolute power, see {@link #getAbsolutePower(String)}.
	 * @param s : spectrometer detected on the system
	 * @param lowerBound : some lower integration bound in nanometers.
	 * @param upperBound : some upper integration bound in nanometers.
	 * @return some total area under the curve.
	 */
	public double spectralIntegration( String s, double lowerBound, double upperBound ) {
		confirmDeviceDetected(s);
		if ( ! validAbsoluteMeasurementConditions( s, lowerBound, upperBound ) ) {
			throw new UnsupportedOperationException( classError );
		}
		// Hard-coded to perform a trapezoidal integration, e.g., parameter 1. See docs.
		return maths.integrate( getWavelengths(s), getSpectrum(s), lowerBound, upperBound, 1 );
	}
	
	/**
	 * Determine if spectrometer is saturated.
	 * @param s : spectrometer detected on system.
	 * @return true if spectrometer is saturated, else, false.
	 */
	public boolean isSaturated( String s ) {
		confirmDeviceDetected(s);
		return spectrometer.isSaturated( spectrometerMap.get(s) );
	}
	
	/**
	 * Get wavelengths (abscissa) associated with spectral measurement. See {@link #getSpectrum(String)}.
	 * @param s : spectrometer detected on system.
	 * @return Array of wavelengths, each in units of nanometers.
	 */
	public double[] getWavelengths( String s ) {
		confirmDeviceDetected(s);
		return spectrometer.getWavelengths( spectrometerMap.get(s) );
	}

	/**
	 * Look for device on system. If not found, throw exception.
	 * @param s : spectrometer detected on system.
	 */
	private void confirmDeviceDetected( String s ) {
		if ( ! spectrometerMap.containsKey(s) ) {
			throw new UnsupportedOperationException( "No such device: '" + s + "'" );
		}
	}
	
	/**
	 * Get spectrum measured at spectrometer. 
	 * @param s : spectrometer detected on system.
	 * @return spectral data (counts per nm)
	 */
	public double[] getSpectrum( String s ) {
		confirmDeviceDetected(s);
		return spectrometer.getSpectrum( spectrometerMap.get(s) );
	}
	
	/**
	 * Get spectrum measured at spectrometer, minus dark spectrum. If no dark spectrum yet collected, method returns spectrum omitting subtraction. 
	 * @param s : spectrometer detected on system.
	 * @return spectral data (counts per nm)
	 */
	public double[] getSpectrumMinusDark( String s ) {
		confirmDeviceDetected(s);
		if ( darkSpectrumMap.get(s) == null ) {
			return getSpectrum(s);
		}
		return ArrayMath.subtractArray( spectrometer.getSpectrum( spectrometerMap.get(s) ), darkSpectrumMap.get(s) );
	}
	
	/**
	 * Get dark spectrum measured at spectrometer.
	 * @param s : spectrometer detected on system.
	 */
	public void getDarkSpectrum( String s ) {
		confirmDeviceDetected(s);
		darkSpectrumMap.put( s, spectrometer.getSpectrum( spectrometerMap.get(s) ) );
	}
	
	/**
	 * Set number of scans to average for spectrometer.
	 * @param s : spectrometer detected on system.
	 * @param i : integer scans to average.
	 */
	public void setScansToAverage( String s, int i ) {
		confirmDeviceDetected(s);
		spectrometer.setScansToAverage( spectrometerMap.get(s), i );
	}
	
	/**
	 * Set integration time for spectrometer.
	 * @param s : spectrometer detected on system.
	 * @param i : integration time in whole microseconds.
	 */
	public void setIntegrationTime( String s, int i ) {
		confirmDeviceDetected(s);
		spectrometer.setIntegrationTime( spectrometerMap.get(s), i );
	}

	/**
	 * Set the trigger mode for the spectrometer. Modes specified here are consistent with spectrometer firmware versions 3 and newer.
	 * See manufacturer API documentation for details: https://www.oceaninsight.com/globalassets/catalog-blocks-and-images/software-downloads-installers/javadocs-api/omnidriver/index.html
	 * @param s : spectrometer detected on the system.
	 * @param i : trigger mode. 0 = normal (free running) mode. 1 = software trigger mode. 2 = external hardware level trigger mode. 3 = external sychronization trigger mode. 4 = external hardware edge trigger mode.
	 */
	public void setExternalTriggerMode( String s, int i ) {
		confirmDeviceDetected(s);
		if ( i < 0 || i > 4 ) {
			throw new UnsupportedOperationException( "Invalid trigger mode specified" );
		}
		spectrometer.setExternalTriggerMode( spectrometerMap.get(s), i );
	}
	
	/**
	 * Get current trigger mode. See {@link #setExternalTriggerMode(String, int)}.
	 * @param s : spectrometer detected on the system.
	 * @return trigger mode; 0 = normal (free running) mode. 1 = software trigger mode. 2 = external hardware level trigger mode. 3 = external sychronization trigger mode. 4 = external hardware edge trigger mode.
	 */
	public int getExternalTriggerMode( String s ) {
		confirmDeviceDetected(s);
		return spectrometer.getExternalTriggerMode( spectrometerMap.get(s) );
	}
	
	/**
	 * Set spectrometer temperature via internal thermoelectric module. Not supported for all devices.
	 * @param s : spectrometer detected on system.
	 * @param d : temperature in C
	 */
	public void setDetectorSetPointCelsius( String s, double d ) {
		confirmDeviceDetected(s);
		if ( ! spectrometer.isFeatureSupportedThermoElectric(spectrometerMap.get(s)) ) {
			throw new UnsupportedOperationException( "Spectrometer '" + s + "' has no thermoelectric temperature control function." );
		}
		// Method returns false if setting failed. If so, run again.
		if ( ! spectrometer.setDetectorSetPointCelsius( spectrometerMap.get(s), d ) ) {
			spectrometer.setDetectorSetPointCelsius( spectrometerMap.get(s), d );
		}
	}
	
	/**
	 * Change electric dark correction setting.
	 * @param s : spectrometer detected on system.
	 * @param i : 0 (off) or 1 (on).
	 */
	public void setCorrectForElectricalDark( String s, int i ) {
		confirmDeviceDetected(s);
		if ( i != 0 & i != 1 ) {
			throw new UnsupportedOperationException( "Electrical dark setting must be 0 (off) or 1 (on)." );
		}
		spectrometer.setCorrectForElectricalDark( spectrometerMap.get(s), i );
	}
	
	/**
	 * Change non-linearity correction setting.
	 * @param s : spectrometer detected on system.
	 * @param i : 0 (off) or 1 (on).
	 */
	public void setCorrectForDetectorNonlinearity( String s, int i ) {
		confirmDeviceDetected(s);
		if ( i != 0 & i != 1 ) {
			throw new UnsupportedOperationException( "Nonlinearity setting must be 0 (off) or 1 (on)." );
		}
		// Method returns false if setting failed. If so, run again.
		if ( ! spectrometer.setCorrectForDetectorNonlinearity( spectrometerMap.get(s), i ) ) {
			spectrometer.setCorrectForDetectorNonlinearity( spectrometerMap.get(s), i );
		}
	}
	
	/**
	 * Set wavelength calibration coefficients into EEProm.
	 * @param s : spectrometer detected on system.
	 * @param zeroth order wavelength calibration coefficient
	 * @param first order wavelength calibration coefficient
	 * @param second order wavelength calibration coefficient
	 * @param third order wavelength calibration coefficient
	 */
	public void setWavelengthCalibrationCoefficients( String s, double zeroth, double first, double second, double third ) {
		Coefficients c = new Coefficients();
		c.setWlIntercept( zeroth );
		c.setWlFirst( first );
		c.setWlSecond( second );
		c.setWlThird( third );
		spectrometer.setCalibrationCoefficientsIntoEEProm( spectrometerMap.get(s), c, true, true, true );
	}
	
	/**
	 * Get current wavelength calibration coefficients from EEProm. 
	 * @param s : spectrometer detected on system.
	 * @return Coefficients object listing all calibration coefficients in a descriptive string.
	 */
	public Coefficients getCalibrationCoefficients( String s ) {
		return spectrometer.getCalibrationCoefficientsFromEEProm( spectrometerMap.get(s) );
	}
	
	/**
	 * Set box car width for one spectrometer detected on system.
	 * @param s : spectrometer detected on system.
	 * @param i : some number of pixels on either side of a center pixel.
	 */
	public void setBoxCarWidth( String s, int i ) {
		confirmDeviceDetected(s);
		if ( i < 0 ) {
			throw new UnsupportedOperationException( "Boxcar width must be greater than 0." );
		}
		spectrometer.setBoxcarWidth( spectrometerMap.get(s), i );
	}
		
	/**
	 * Recover integration time for a spectrometer.
	 * @param s : spectrometer detected on system.
	 * @return integer integration time in microseconds.
	 */
	private int getIntegrationTime( String s ) {
		confirmDeviceDetected(s);
		return spectrometer.getIntegrationTime( spectrometerMap.get(s) );
	}
	
	/**
	 * Compute the absolute power of a spectrum in units of microwatts (uW).
	 * @param s : spectrometer detected on system.
	 * @param @param scaleFactor : a value by which each pixel in the spectrum is multiplied (if desired). Use this parameter to change units or scale result. If no scaling desired, use scale factor = 1.
	 * @param lowerBound : some lower integration bound in nanometers.
	 * @param upperBound : some upper integration bound in nanometers.
	 * @return total power in microwatts.
	 */
	public double getAbsolutePower( String s, double scaleFactor, double lowerBound, double upperBound ) {
		confirmDeviceDetected(s);
		// Irradiance
        uWPerCmSquaredPerNm = getAbsoluteIrradiance( s, scaleFactor, lowerBound, upperBound );
        // Multiply out the 'collectionArea' and integrate over wavelengths (pixels) to get absolute uWatts.
        uWatts = AdvancedPhotometrics.compute_uWatt				(
        							getWavelengths(s),
        							uWPerCmSquaredPerNm, 
        							lowerBound, 
        							upperBound, 
        							INTEGRATION_METHOD,
        							collectionArea				);
        // Return microwatts, rounded to three decimal places.
        return Math.round( uWatts * 1000.0 ) / 1000.0;
	}
	 
	/**
	 * Yields irradiance in units of microwatts per square cm per nm (uW / cm^2 ) / nm.
	 * @param s : spectrometer detected on system.
	 * @param scaleFactor : a value by which each pixel in the spectrum is multiplied (if desired). Use this parameter to change units or scale result. If no scaling desired, use scale factor = 1.
	 * @param lowerBound : some lower integration bound in nanometers.
	 * @param upperBound : some upper integration bound in nanometers.
	 * @return
	 */
	public double[] getAbsoluteIrradiance( String s, double scaleFactor, double lowerBound, double upperBound ) {
		if ( ! validAbsoluteMeasurementConditions( s, lowerBound, upperBound ) ) {
			throw new UnsupportedOperationException( classError );
		}
		irradiance = AdvancedAbsoluteIrradiance.processSpectrum(
							darkSpectrumMap.get(s), 
							spectrometer.getSpectrum( spectrometerMap.get(s) ), // a fresh spectrum, NOT a spectrum minus dark!
							getWavelengths(s), 
							calibrationSpectrumMap.get(s),
							getIntegrationTime(s), 
							collectionArea, 
							false								);
		// User can choose to scale entire spectrum of irradiance by scalar. 10^6 scalar added here to achieve uW units.
        return 	ArrayMath.multiplyConstant(
							irradiance,
							MICRO_WATT_SCALAR * scaleFactor				);
	}
	
	/**
	 * Yields radiance in units of microwatts per square cm steradian per nm, e.g., (uW / cm^2 * str ) / nm.
	 * @param s : spectrometer detected on system.
	 * @param scaleFactor : a value by which each pixel in the spectrum is multiplied (if desired). Use this parameter to change units or scale result. If no scaling desired, use scale factor = 1.
	 * @param solidAngle : a solid angle in steradians formed by the half angle unique to an illumination optic.
	 * @param opticOutputArea : the optical output area of the illumination optic, in square cm.
	 * @param lowerBound : some lower integration bound in nanometers.
	 * @param upperBound : some upper integration bound in nanometers.
	 * @return
	 */
	public double[] getAbsoluteRadiance( String s, double scaleFactor, double solidAngle, double opticOutputArea, double lowerBound, double upperBound ) {
		if ( ! validAbsoluteMeasurementConditions( s, lowerBound, upperBound ) ) {
			throw new UnsupportedOperationException( classError );
		}
		irradiance = AdvancedAbsoluteIrradiance.processSpectrum(
							darkSpectrumMap.get(s), 
							spectrometer.getSpectrum( spectrometerMap.get(s) ), // a fresh spectrum, NOT a spectrum minus dark!
							getWavelengths(s), 
							calibrationSpectrumMap.get(s),
							getIntegrationTime(s), 
							collectionArea, 
							false								);
		// User can choose to scale entire spectrum of radiance by scalar, scaleFactor.
        return 	ArrayMath.multiplyConstant(
							irradiance,
							MICRO_WATT_SCALAR * (1/Math.PI) * (1/solidAngle) *  (1/Math.PI) * (1/opticOutputArea) * scaleFactor	);
	}
		
	/**
	 * Convenience method should be called first for any method seeking to recover absolute measurements using
	 * calibrations and integration. Method can also be employed from the client before calling absolute measurement methods.
	 * @param s : spectrometer detected on system.
	 * @param lowerBound : some lower integration bound in nanometers.
	 * @param upperBound : some upper integration bound in nanometers.
	 */
	public boolean validAbsoluteMeasurementConditions( String s, double lowerBound, double upperBound ) {
		if ( darkSpectrumMap.get(s) == null ) {
			classError = "No dark spectrum for device " + s + ". First call #getDarkSpecrum.";
			return false;
		}
		if ( calibrationSpectrumMap.get(s) == null ) {
			classError = "Calibration missing or failed to load.";
			return false;
		}
		if ( spectrometer.getNumberOfPixels(spectrometerMap.get(s)) != calibrationSpectrumMap.get(s).length ) {
			classError = "Length of calibration data array does not match raw data length.";
			return false;
		}
		if ( lowerBound >= upperBound ) {
			classError = "Lower integration bound must be less than upper integration bound.";
			return false;
		}
		if ( upperBound > getWavelengths(s)[ getWavelengths(s).length-1 ] ) {
			classError = "Specified high-side integration bound does not exist.";
			return false;
		}
		if ( lowerBound < getWavelengths(s)[ 0 ] ) {
			classError = "Specified low-side integration bound does not exist.";
			return false;
		}
		return true;
	}
	
	/**
	 * Allow client to establish directory for additional calibration data within the running program.
	 * When used in the client application, first call this method to point the program
	 * at the correct calibration data. Then, call {@link #loadCalibrationForFilter(String, String)}
	 * each time the ND filter changes to automatically apply calibration files. Directory must specify 
	 * folder containing calibration files for all ND filters.
	 * @param path specifying folder containing calibration data for all ND filters
	 */
	public void setCalibrationDirectoryJar( String path ) {
		if ( Controller.runningAsJAR() ) {
			jarCalibrationDirectoryDefault = path;
			return;
		}
		ideCalibrationDirectoryDefault = path;
	}
	
	/**
	 * Load calibration into volatile memory for use in {@link #getAbsolutePower(String, double, double, double)}. 
	 * All calibration files should be located in '/src/spectrometer/defaultcalibrationX.X.calibration', where X.X is an appropriate ND filter value, when running from IDE.
	 * When running from JAR, all calibration files must be in [jar_parent_directory]/spectrometer/defaultcalibrationX.X.calibration
	 * @param s : spectrometer detected on the system
	 * @param optical density of a filter for which there is a calibration on file.
	 */
	public void loadCalibrationForFilter( String s, String filterOd ) {
		if ( Controller.runningAsJAR() ) {
			loadCalibrationFromFile( s, jarCalibrationDirectoryDefault + File.separator + "defaultcalibration" + filterOd + ".calibration" );
			return;
		} 
		loadCalibrationFromFile( s, ideCalibrationDirectoryDefault + File.separator + "defaultcalibration" + filterOd + ".calibration" );
	}
	
	/**
	 * General method to read a calibration from file.
	 * @param s : spectrometer detected on the system
	 * @param path to where calibration file lives.
	 */
    private void loadCalibrationFromFile( String s, String filePath ) {
    	calibrationFileContent.clear();
        try {
			br = new BufferedReader( new FileReader(filePath) );
			while ( ( fileLine = br.readLine() ) != null ) {
			    calibrationFileContent.add( fileLine );
			}
			br.close();
		} catch ( IOException e ) {
			System.err.println( "### ### ### ###     !     ### ### ### ###     !     ### ### ### ###     !     ### ### ### ###" );
			System.err.println( "### ### ### ###     !     ### ### ### ###     !     ### ### ### ###     !     ### ### ### ###" );
			System.err.println( "### ### ### ###     !     ### ### ### ###     !     ### ### ### ###     !     ### ### ### ###" );
			System.err.println( "ERROR! An error occured loading spectrometer calibrations. All calibrations must be in: " + filePath + "\n" );
			System.err.println( "### ### ### ###     !     ### ### ### ###     !     ### ### ### ###     !     ### ### ### ###" );
			System.err.println( "### ### ### ###     !     ### ### ### ###     !     ### ### ### ###     !     ### ### ### ###" );
			System.err.println( "### ### ### ###     !     ### ### ### ###     !     ### ### ### ###     !     ### ### ### ###" );
		} 
        int pixelCount = Integer.parseInt( calibrationFileContent.get(PIXEL_COUNT_LINE) );
        calibrationValues = new double[ pixelCount ];
        for (int i = 0; i < Integer.parseInt( calibrationFileContent.get(PIXEL_COUNT_LINE) )-1; i++) {
            calibrationValues[i] = Double.parseDouble( calibrationFileContent.get( i + DATA_START_LINE ) );
        }
        calibrationSpectrumMap.put( s, calibrationValues );
    }
    
	/**
	 * A sample routine for testing only.
	 * @param args
	 * @throws InterruptedException
	 * @throws SerialPortTimeoutException 
	 * @throws SerialPortException 
	 */
	public static void main(String[] args) throws InterruptedException, SerialPortException, SerialPortTimeoutException {
		OceanOpticsSpectrometer s = new OceanOpticsSpectrometer( );
//		ThorLabsFilterWheel w = new ThorLabsFilterWheel( "COM3" ); // For use with ThorLabs filter wheel, ND filters
//		SpectrumPlotter p = new SpectrumPlotter( s ); // Simple JavaFX utility for viewing spectrum
		String spectrometer = s.refreshSpectrometerList().get(0);
		s.loadCalibrationForFilter( spectrometer, "0.1" );
		s.setIntegrationTime( spectrometer, 1000 );
		s.setBoxCarWidth( spectrometer, 3 );
		s.setScansToAverage( spectrometer, 3 );
		s.setCorrectForElectricalDark( spectrometer, 1 );
		s.setCorrectForDetectorNonlinearity( spectrometer, 1 );
		s.getDarkSpectrum( spectrometer );
		System.out.println( "Collecting dark..." );
		Thread.sleep( 3000 );
		System.out.println( "Power (uW): " + s.getAbsolutePower( spectrometer, 1, 300, 900) );
		double[] array = s.getAbsoluteIrradiance( spectrometer, 1, 300, 900 );
		for ( int i = 0; i < array.length-1; i++ ) {
			System.out.println( array[i] );
		}
	}

}
