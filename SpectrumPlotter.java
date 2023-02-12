package com.ioi.utilities;

import java.net.URISyntaxException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javafx.animation.AnimationTimer;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.stage.Window;
import jssc.SerialPortException;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;

/**
 * Sample utility for use with OceanOpticsSpectrometer. Not intended as stand-alone application!
 * @author Phillip Curtsmith
 * @since Spring 2022
 *
 */
public class SpectrumPlotter {

	// Concurrency
	private AnimationTimer timer;
	private ExecutorService executor = Executors.newSingleThreadExecutor();
	private Future<String> futureTask;
	private Dialog<?> dialog;
	
	// Chart objects
	private double[] testDataX = { };
	private double[] testDataY = { };
	private XYChart.Series series = new XYChart.Series();
	private int lowerIntegrationBound = 300;
	private int upperIntegrationBound = 900;
	
	// Spectrometer API details and data
	private static OceanOpticsSpectrometer spectrometerObject;
	private ObservableList<Double> peaks;
	private String peakWavelengths;
	private String peakIndices;
	private int averagingDefaultValue = 3;
	private int integrationTimeDefaultValue = 1000;
	private ObservableList<String> devices;
	private String spectrometerString;
	
	// Hardware
	private ThorLabsFilterWheel filter;

	// GUI objects for listeners
	private ChoiceBox<String> deviceSelect;
	private Button subtractDarkButton;
	private ChoiceBox<String> filterWheelSelect;
	private ChoiceBox<String> filterWheelComPort;
	private Button refreshFilterWheelComPorts;
	private Button refreshDeviceButton;

	private SpectrumPlotter() { }

	public SpectrumPlotter( OceanOpticsSpectrometer s ) {
		spectrometerObject = s;
		dialog = new Dialog<>();
		dialog.setTitle( "Prometheus Spectrum Plotter" );
		dialog.getDialogPane().getStylesheets().add( "style.css" );
		dialog.setResizable( false );
		Window window = dialog.getDialogPane().getScene().getWindow();
		
		// Determine dialog closing behavior. Clear resources.
		window.setOnCloseRequest( event -> {					
					subtractDarkButton.removeEventFilter( MouseEvent.MOUSE_CLICKED, subtractDarkButtonEvent );
					refreshFilterWheelComPorts.removeEventFilter( MouseEvent.MOUSE_CLICKED, refreshFilterWheelComPortsEvent );
					refreshDeviceButton.removeEventFilter( MouseEvent.MOUSE_CLICKED, refreshDeviceButtonEvent );
					dialog.close();
					closeFilterWheelPort();
					executor.shutdownNow();
					timer.stop();
		});

		// Get images for buttons
		Image subtractDarkImage = null;
		Image refreshDeviceImage = null;
		try {
			subtractDarkImage = new Image( getClass().getResource( "/resources/collect_dark_spectrum.png" ).toURI().toString() );
			refreshDeviceImage = new Image( getClass().getResource( "/resources/refresh_devices.png" ).toURI().toString() );
		} catch ( URISyntaxException e ) { e.printStackTrace(); }

		GridPane gridPane = new GridPane();
		dialog.getDialogPane().setContent( gridPane );
		
		Label deviceLabel = new Label( "Device:" );
		refreshDeviceButton = new Button(); refreshDeviceButton.setGraphic( new ImageView( refreshDeviceImage ) ); refreshDeviceButton.setPrefWidth( 20 );

		subtractDarkButton = new Button(); subtractDarkButton.setGraphic( new ImageView( subtractDarkImage ) ); subtractDarkButton.setPrefWidth( 20 );
		deviceSelect = new ChoiceBox<String>(); deviceSelect.setPrefWidth( 130 );
		HBox deviceHBox = new HBox(); deviceHBox.getChildren().add( 0, deviceSelect ); deviceHBox.getChildren().add( 1, refreshDeviceButton ); deviceHBox.getChildren().add( 2, subtractDarkButton );
		deviceHBox.setMargin( refreshDeviceButton, new Insets(0,0,0,5) );
		deviceHBox.setMargin( subtractDarkButton, new Insets(0,0,0,5) );
		Label radiometricFluxLabel = new Label( "Radiometric Flux (mW):" );
		TextField radiometricFluxTextField = new TextField(); radiometricFluxTextField.setEditable( false ); radiometricFluxTextField.setPrefWidth( 200 ); radiometricFluxTextField.setFocusTraversable( false );
		Label peakWavelengthsLabel = new Label( "Peaks (nm):" );
		TextArea peaksWavelengthsTextField = new TextArea(); peaksWavelengthsTextField.setEditable( false ); peaksWavelengthsTextField.setMaxWidth( 200 ); peaksWavelengthsTextField.setFocusTraversable( false ); peaksWavelengthsTextField.setWrapText( true ); peaksWavelengthsTextField.setPrefHeight( 43 );
		Label peakIndexLabel = new Label( "Peak index/ices:" );
		TextArea peakIndexTextField = new TextArea(); peakIndexTextField.setEditable( false ); peakIndexTextField.setMaxWidth( 200 ); peakIndexTextField.setFocusTraversable( false ); peakIndexTextField.setWrapText( true ); peakIndexTextField.setPrefHeight( 43 );
		Label integrationTimeLabel = new Label( "Integration time (us):" );
		Spinner<Integer> integrationTimeSelect = new Spinner<Integer>( 100, 100000, integrationTimeDefaultValue ); integrationTimeSelect.setEditable( true ); integrationTimeSelect.setPrefWidth( 200 );
		Label scansToAverageLabel = new Label( "Scans to average:" );
		Spinner<Integer> scansToAverageSelect = new Spinner<Integer>( 1, 500, averagingDefaultValue ); scansToAverageSelect.setEditable( true ); scansToAverageSelect.setPrefWidth( 200 );
		Label boxCarAverageLabel = new Label( "Box car width:" );
		Spinner<Integer> boxCarAverageSelect = new Spinner<Integer>( 1, 500, averagingDefaultValue ); boxCarAverageSelect.setEditable( true ); boxCarAverageSelect.setPrefWidth( 200 );

		Label filterWheelLabel = new Label( "Filter (OD):" );
		refreshFilterWheelComPorts = new Button(); refreshFilterWheelComPorts.setGraphic( new ImageView( refreshDeviceImage ) ); refreshDeviceButton.setPrefWidth( 20 );
		filterWheelSelect = new ChoiceBox<String>(); filterWheelSelect.setPrefWidth( 80 ); 
		filterWheelComPort = new ChoiceBox<String>(); filterWheelComPort.setPrefWidth( 80 );
		HBox filterHBox = new HBox(); filterHBox.getChildren().add( 0, refreshFilterWheelComPorts ); filterHBox.getChildren().add( 1, filterWheelComPort ); filterHBox.getChildren().add( 2, filterWheelSelect );
		deviceHBox.setMargin( filterWheelSelect, new Insets(0,0,0,5) );
		deviceHBox.setMargin( filterWheelComPort, new Insets(0,0,0,5) );

		NumberAxis xAxis = new NumberAxis();
		NumberAxis yAxis = new NumberAxis();

		xAxis.setLabel( "Wavelength (nm)" ); 
		yAxis.setLabel( "Counts" );
		yAxis.setForceZeroInRange( false );
		xAxis.setForceZeroInRange( false );

		LineChart< Number, Number > lineChart = new LineChart< Number, Number >(xAxis,yAxis);
		lineChart.setTitle( "Real-time Spectrum" );        
		lineChart.getData().add( series );
		lineChart.setCreateSymbols( false );
		lineChart.setAnimated( false );
		lineChart.setLegendVisible( false );
		lineChart.setPrefHeight( 600 );
		lineChart.setPrefWidth( 800 );

		// GridPane.add( Node, col, row )
		// new Insets( top, right, bottom, left )
		gridPane.add( lineChart, 0, 0 );
		GridPane.setRowSpan( lineChart, GridPane.REMAINING );
		gridPane.add( deviceLabel, 1, 0 );					gridPane.setMargin( deviceLabel, new Insets(40,0,0,0) );
		gridPane.add( deviceHBox, 1, 1 );
		gridPane.add( radiometricFluxLabel, 1, 2 ); 		gridPane.setMargin( radiometricFluxLabel, new Insets(10,0,0,0) );
		gridPane.add( radiometricFluxTextField, 1, 3 ); 	gridPane.setMargin( radiometricFluxTextField, new Insets(0,10,10,0) );
		gridPane.add( peakWavelengthsLabel, 1, 4 );
		gridPane.add( peaksWavelengthsTextField, 1, 5 );
		gridPane.add( peakIndexLabel, 1, 6 ); 				gridPane.setMargin( peakIndexLabel, new Insets(10,0,0,0) );
		gridPane.add( peakIndexTextField, 1, 7 ); 
		gridPane.add( integrationTimeLabel, 1, 8 );			gridPane.setMargin( integrationTimeLabel, new Insets(10,0,0,0) );
		gridPane.add( integrationTimeSelect, 1, 9 );
		gridPane.add( scansToAverageLabel, 1, 10 );			gridPane.setMargin( scansToAverageLabel, new Insets(10,0,0,0) );
		gridPane.add( scansToAverageSelect, 1, 11 );
		gridPane.add( boxCarAverageLabel, 1, 12 );			gridPane.setMargin( boxCarAverageLabel, new Insets(10,0,0,0) );
		gridPane.add( boxCarAverageSelect, 1, 13 );
		gridPane.add( filterWheelLabel, 1, 14 );			gridPane.setMargin( filterWheelLabel, new Insets(10,0,0,0) );
		gridPane.add( filterHBox, 1, 15 );
		gridPane.add( new Pane(), 1, 16 );

		// Listen for user filter OD selection
		// Note: attempted to implement listener with EventHandler, but found that Event.ANY behaves differently than
		// object.setOnAction. No apparent loss in efficiency, so, went with lambda.
		// filterWheelSelect.addEventHandler( Event.ANY, filterWheelSelectEvent );
		filterWheelSelect.setOnAction( event -> {
			if ( filterWheelSelect.getSelectionModel().getSelectedItem() == null ) {
				return;
			}
			try {
				filter.setPosition( filter.convertOdToPos(filterWheelSelect.getSelectionModel().getSelectedItem()) );
			} catch (SerialPortException | InterruptedException e) {
				System.err.println( "An error occured while assigning filter wheel position in SpectrumPlotter.java. Exception: " + e.getClass() );
				return;
			}
			spectrometerObject.loadCalibrationForFilter( spectrometerString, filterWheelSelect.getSelectionModel().getSelectedItem() );
		});
		
		// Refresh COM port selection for filter wheel
		refreshFilterWheelComPorts.addEventHandler( MouseEvent.MOUSE_CLICKED, refreshFilterWheelComPortsEvent );

		// listen for changes to filter wheel com port selection
		filterWheelComPort.setOnAction( event -> {
			if ( filterWheelComPort.getSelectionModel().getSelectedItem() == null ) {
				return;
			}
			closeFilterWheelPort();
			futureTask = executor.submit( new Timeout( filterWheelComPort ) );
			try {
				futureTask.get( 500, TimeUnit.MILLISECONDS );
			} catch ( TimeoutException | InterruptedException | ExecutionException e ) {
				System.err.println( "An error occured during COM port timeout. Exception: " + e.getClass() );
				futureTask.cancel( true );
			}
			if ( filter == null ) {
				System.out.println( "null!" );
				filterWheelSelect.getItems().clear();
				return;
			}       	
			filterWheelSelect.getItems().addAll( filter.getFilterODs() );
			filterWheelSelect.getSelectionModel().select( "0" );
		});

		// Refresh filter wheel COM on program start
		filterWheelComPort.getItems().addAll( DeviceIdentifier.getAvailablePorts() );
		if ( filterWheelComPort.getItems().contains( "COM101" ) ) {
			filterWheelComPort.getSelectionModel().select( "COM101" );
		}

		// Refresh devices on program start
		refreshDevices( deviceSelect );

		// Listen for when user selects refresh button
		refreshDeviceButton.addEventHandler( MouseEvent.MOUSE_CLICKED, refreshDeviceButtonEvent );

		// Listen for when user selects collect dark spectrum button
		subtractDarkButton.addEventHandler( MouseEvent.MOUSE_CLICKED, subtractDarkButtonEvent );

		// Refresh chart data at regular interval from within JavaFX thread
		// Not optimized.
		timer = new AnimationTimer() {
			long lastUpdate = 0;
			long delay = 250_000_000;
			@Override
			public void handle(long now) {
				if ( now - lastUpdate >= delay ) {
					spectrometerString = deviceSelect.getValue();
					if ( spectrometerString == null ) {
						peaksWavelengthsTextField.clear();
						peakIndexTextField.clear();
						radiometricFluxTextField.clear();
						integrationTimeSelect.getValueFactory().setValue( integrationTimeDefaultValue );
						boxCarAverageSelect.getValueFactory().setValue( averagingDefaultValue );
						scansToAverageSelect.getValueFactory().setValue( averagingDefaultValue );
						return;
					} 
					if ( spectrometerObject.isSaturated( spectrometerString ) ) {
						lineChart.setStyle( "CHART_COLOR_1: #FF0000;" );
					} else {
						lineChart.setStyle( "CHART_COLOR_1: #ffffff;" );
					}
					// refresh spectral data
					refreshSpectrumData();
					// print peak wavelengths to window
					peakWavelengths = spectrometerObject.getPeakWavelengths( spectrometerString, 15, 3000 ).toString();
					peaksWavelengthsTextField.setText( peakWavelengths.substring( 1, peakWavelengths.length()-1 ) );
					// print peak indices to window
					peakIndices = spectrometerObject.getPeakIndices( spectrometerString, 15, 3000 ).toString();
					peakIndexTextField.setText( peakIndices.substring( 1, peakIndices.length()-1 ) );
					// print radiometric flux
					if ( spectrometerObject.validAbsoluteMeasurementConditions( spectrometerString, lowerIntegrationBound, upperIntegrationBound ) ) {
						// Absolute power scaled here to be mW rather than uW
						radiometricFluxTextField.setText( String.valueOf( spectrometerObject.getAbsolutePower(spectrometerString, 0.001, lowerIntegrationBound, upperIntegrationBound) ) );
					} else {
						radiometricFluxTextField.setText( "Load filter and collect dark spectrum" );
					}
					// set integration time
					spectrometerObject.setIntegrationTime( spectrometerString, integrationTimeSelect.getValue() );
					// set scans to average
					spectrometerObject.setScansToAverage( spectrometerString, scansToAverageSelect.getValue() );
					// set box car width
					spectrometerObject.setBoxCarWidth( spectrometerString, boxCarAverageSelect.getValue() );
					// update timer timing
					lastUpdate = now ;
				}
			}

		};
		timer.start();
		dialog.show();
	}
	
	EventHandler<MouseEvent> subtractDarkButtonEvent = new EventHandler<MouseEvent>() { 
		@Override 
		public void handle( MouseEvent e ) { 
			if ( deviceSelect.getValue() == null ) {
				return;
			}
			spectrometerObject.getDarkSpectrum( deviceSelect.getValue() );
		} 
	};
	
	// See note from above. Kept for reference.
//	EventHandler<Event> filterWheelSelectEvent = new EventHandler<Event>() {
//		@Override
//		public void handle(Event event) {
//			if ( filterWheelSelect.getSelectionModel().getSelectedItem() == null ) {
//				return;
//			}
//			try {
//				filter.setPosition( filter.convertOdToPos(filterWheelSelect.getSelectionModel().getSelectedItem()) );
//			} catch (SerialPortException | InterruptedException e) {
//				System.err.println( "An error occured while assigning filter wheel position in SpectrumPlotter.java. Exception: " + e.getClass() );
//				return;
//			}
//			spectrometerObject.loadCalibrationForFilter( filterWheelSelect.getSelectionModel().getSelectedItem() );
//		}
//	};
	
	EventHandler<MouseEvent> refreshFilterWheelComPortsEvent = new EventHandler<MouseEvent>() {
		@Override
		public void handle(MouseEvent event) {
			filterWheelComPort.getItems().clear();
			filterWheelSelect.getItems().clear();
			filterWheelComPort.getItems().addAll( DeviceIdentifier.getAvailablePorts() );
			closeFilterWheelPort();
			if ( filterWheelComPort.getItems().contains( DeviceIdentifier.noAvailablePortFlag() ) ) {
				filterWheelComPort.getItems().clear();
			}
		}
	};
		
	EventHandler<MouseEvent> refreshDeviceButtonEvent = new EventHandler<MouseEvent>() {
		@Override
		public void handle(MouseEvent event) {
			refreshDevices( deviceSelect );
		}
	};
		
	class Timeout implements Callable<String> {
		ChoiceBox<String> filterWheelComPort;
		Timeout( ChoiceBox<String> cb ) { filterWheelComPort = cb; };
		@Override
		public String call() throws Exception {
			filter = new ThorLabsFilterWheel( filterWheelComPort.getSelectionModel().getSelectedItem() );
			return "";
		}
	}

	private void closeFilterWheelPort() {
		if ( filter != null ) {
			if ( filter.isPortOpen() ) {
				try {
					filter.closePort();
				} catch (SerialPortException e) {
					System.err.println( "Error closing filter wheel port. Port may be missing or already closed. Exception: " + e.getClass() );
				}
				filter = null;
			}
		}
	}

	private void refreshDevices( ChoiceBox<String> deviceSelect ) {
		deviceSelect.getItems().clear();
		devices = spectrometerObject.refreshSpectrometerList();
		if ( devices.size() == 0 ) {
			return;
		}
		deviceSelect.getItems().addAll( devices );
		deviceSelect.setValue( devices.get(0) );
	}

	private void refreshSpectrumData() {
		series.getData().clear();
		testDataX = spectrometerObject.getWavelengths( spectrometerString );
		testDataY = spectrometerObject.getSpectrumMinusDark( spectrometerString );
		for ( int i = 0; i < testDataX.length; i++ ) {
			series.getData().add( new XYChart.Data( testDataX[i], testDataY[i] ) );
		}
	}

	/**
	 * Testing only.
	 * @param args
	 */
	public static void main(String[] args) {
		OceanOpticsSpectrometer s = new OceanOpticsSpectrometer( );
		SpectrumPlotter sp = new SpectrumPlotter( s ); 
	}
}
