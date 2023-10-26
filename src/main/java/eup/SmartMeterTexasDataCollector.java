package eup;

import java.awt.Container;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;


/**
 * A <tt>SmartMeterTexasDataCollector</tt> represents the actions needed to
 * access the
 * web pages containing the electrical meter data at smartmetertexas.com.
 * Access is via <tt>GET</tt> and <tt>POST</tt> methods accessed by using the
 * HTTP protocol. Most cookies are automatically handled.
 * <p>
 * 
 * @author Ian Shef
 * @version 1.0 25 Aug 2018
 * @version 1.1 12 Sep 2023
 * 
 * @since 1.0
 * 
 */

/*
 * Playwright javadocs are at
 * https://javadoc.io/doc/com.microsoft.playwright/playwright/1.18.0/index.html
 * 
 * 
 * Eliminate error messages when using Playwright:
 * 
 * Gtk-Message:
 * <timestamp>: Failed to load module "canberra-gtk-module"
 * 
 * by doing in a terminal:
 * sudo apt-get install libcanberra-gtk-module //
 */

/*
 * Watch out for <span> containing <div> with
 * 
 * Your request could not be processed at this time. Please try again later.
 * 
 */

public class SmartMeterTexasDataCollector implements 
SmartMeterTexasDataInterface
{
    private static final boolean DEBUG_SHOW_MESSAGES = false ;
    private static final boolean DEBUG_SHOW_BROWSER = false ;
    private static final boolean DEBUG_SLOW_BROWSER = false ;
    private static final int BROWSER_DELAY_MILLIS = 2_000 ;

    // The following can be enabled to use a proxy.
    //
    private static final boolean DEBUG_USE_PROXY = false ;
    
    /*
     * Some fields are volatile due to access from multiple threads.
     */
    private volatile LocalDate date ; // The date of this object.
    private volatile int startRead ;

    private volatile boolean dateChanged = false ;
    private volatile boolean dataValid = false ;

    private final Object lock = new Object() ;
    
    private static final int RETRY_LIMIT = 5 ;
    private static final int DATA_RETRY_LIMIT = 5 ;
    private static final int DATA_RETRY_MILLIS = 1000 ;
    private static final String SEARCH_FOR = "(Kwh)" ;
    private static final String TITLE = "Dashboard" ;
//    private static final String GET_URL = "http://smartmetertexas.com" ;
    private static final String GET_URL = "http://www.smartmetertexas.com" ;
    private static final String NO_DATA = "No data available " +
	    "for the date range you requested." ;
    private final static String EMPTY = "" ;
    private static final DateTimeFormatter DATE_PATTERN = 
	    DateTimeFormatter.ofPattern("MM'/'dd'/'yyyy") ;
    private static final String MAINTENANCE = 
	    "Smart Meter Texas is currently undergoing maintenance." ;
    private static final BrowserName browserName = BrowserName.FIREFOX ;
  
    //
    // The following are:
    // volatile due to potential access from multiple threads, and
    // static so that values are maintained throughout the
    //        lifetime of this class.
    //
    static volatile LocalDate cachedDate ;
    static volatile long cachedMeterReading ;
    static volatile boolean cachedValuesValid = false ;
    static volatile boolean cachedValuesUsed  = false ;
    final Object cacheLock                    = new Object() ;

    Feedbacker fb;

    String addressSuffix = "" ;
    
    private int progressStart ;
    private int progressDelta ;
    private String progressLabel ;
    
    private int progress ;

    static final AtomicInteger ai = new AtomicInteger() ;

    /**
     * No publicly-available no-argument constructor.
     */
    private SmartMeterTexasDataCollector() {
	    /*
	     * vvvv    To get rid of warnings. vvvvvvvvvvvvvvvvvvvvvvvvvvvvvv
	     */
	msg(Integer.valueOf(progressDelta)) ;
	msg(Integer.valueOf(progressLabel)) ;
	msg(Integer.valueOf(progress)) ;
	    /*
	     * ^^^^    To get rid of warnings. ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
	     */
    }

    /**
     * A private constructor for getting
     * Smart Meter of Texas information
     * from my electrical meter.  Use
     * the builder SmartMeterTexasDataCollector.Builder
     * to instantiate a SmartMeterTexasDataCollector.
     * 
     */
    @SuppressWarnings("synthetic-access")
    private SmartMeterTexasDataCollector(Builder builder) {
	this.date = builder.date ;
	this.progressStart = builder.progressStart ;
	this.progressDelta = builder.progressDelta ;
	this.progressLabel = builder.progressLabel ;
	progress = progressStart ;

	if (DEBUG_SHOW_MESSAGES) msg("Built " + this) ;
	
    }

    //
    // Returns a CharSequence (e.g. a String) which is the 
    // input CharSequence (e.g. a String) with the last n characters removed.
    //
    public CharSequence clipEnd(CharSequence in, int n) {
	if (n<0) throw new Error("n less than 0 in CharSequence") ;
	return in.subSequence(0, in.length()-(1+n)) ;
    }
    
    @Override
    public void setFeedbacker(Feedbacker fb) {
	this.fb = fb;
    }

    static Feedbacker setupFeedbacker() {
	final ArrayList<Feedbacker> holder = Util.makeArrayList(1);
	try {
	    javax.swing.SwingUtilities.invokeAndWait((new Runnable() {
		@Override
		public void run() {
		    final FeedbackerImplementation fb1 = 
			    new FeedbackerImplementation();
		    JFrame frame = new JFrame(fb1.toString());
		    Container cp = frame.getContentPane();
		    cp.setLayout(new BoxLayout(cp, BoxLayout.Y_AXIS));
		    cp.add(fb1.getProgressBar());
		    cp.add(fb1.getOperationsLog());
		    frame.setDefaultCloseOperation(
			    WindowConstants.EXIT_ON_CLOSE);
		    frame.pack();
		    frame.setVisible(true);
		    System.setOut(new PrintStream(new FeedbackerOutputStream(
			    fb1, "<font color=\"green\">")));
		    System.setErr(new PrintStream(new FeedbackerOutputStream(
			    fb1, "<font color=\"red\">")));
		    holder.add(fb1);
		} // end of run()
	    }));
	} catch (InterruptedException e) {
	    e.printStackTrace();
	} catch (InvocationTargetException e) {
	    e.printStackTrace();
	}
	return holder.get(0);
    }

    public static class Builder {
	// Required parameters
	private LocalDate date ;
	private int progressStart = 20 ;
	private int progressDelta = 20 ;
	private String progressLabel = "Get Data" ;
	
	// Optional parameters initialized to default values - NONE
	
	public Builder date(LocalDate dateOfSMT) {
	    date = dateOfSMT ;  
	    return this ;
	}
	
	public Builder startProgressAt(int startProgressAt) {
	    progressStart = startProgressAt ;
	    return this ;
	}
	
	public Builder changeProgressBy(int changeProgressBy) {
	    progressDelta = changeProgressBy ;
	    return this ;
	}
	
	public Builder labelTheProgress(String labelForTheProgress) {
	    progressLabel = labelForTheProgress ;
	    return this ;
	}
	
	@SuppressWarnings("synthetic-access")
	public SmartMeterTexasDataCollector build() {
	    return new SmartMeterTexasDataCollector(this) ;
	}
    }
    
    /**
     * A main program for testing purposes to develop and test web access.
     * 
     * @param args
     *            Required but currently unused.
     */
    public static void main(String[] args) {
	ElectricityUsagePredictor.main(null) ;
    }

    @Override
    public String toString() {
	return new String(getClass().getName() + " for " + date + ".");
    }

    /**
     * A convenience method for displaying a line of text on System.out.
     * 
     * @param ob
     *            An <tt>Object</tt> or a <tt>String</tt> to be displayed on
     *            System.out. If an <tt>Object</tt>, its toString() method will
     *            be called.
     */
    void msg(Object ob) {
	if (null == fb) {
	    System.out.println(ob);
	} else {
	    fb.log(ob, Feedbacker.TO_OUT + Feedbacker.TO_FILE);
	}
    }

    /**
     * A convenience method for displaying a line of text on System.out
     * using the Event Dispatch Thread.
     * 
     * @param ob
     *            An <tt>Object</tt> or a <tt>String</tt> to be displayed on
     *            System.out. If an <tt>Object</tt>, its toString() method will
     *            be called.
     */
    void msgEDT(Object ob) {
	SwingUtilities.invokeLater(new Runnable() {
	    @Override
	    public void run() {
		msg(ob) ;
	    }
	}) ;
    }

    @SuppressWarnings("boxing")
    private final static BrowserType.LaunchOptions 
    useProxy(BrowserType.LaunchOptions btlo) {
	int result = -1 ;
	/*
	 * 
NOTE: you must make sure you are NOT on the EDT when you call this code, 
as the get() will never return and the EDT will never be released to go 
execute the FutureTask...  Eric Lindauer Nov 20 '12 at 6:08
	 *
	 */
	Callable<Integer> c = new Callable<Integer>() {
	    @Override public Integer call() {
		return JOptionPane.showConfirmDialog(null,
			"Do you want to use the proxy?") ;
	    }
	} ;
	FutureTask<Integer> dialogTask = 
		new FutureTask<Integer>(c);
	if (SwingUtilities.isEventDispatchThread()) {
	    try {
		result = c.call() ;
	    } catch (Exception e) {
		e.printStackTrace();
	    }
	} else {
	    try {
		SwingUtilities.invokeAndWait(dialogTask);
	    } catch (InvocationTargetException e1) {
		e1.printStackTrace();
	    } catch (InterruptedException e1) {
		e1.printStackTrace();
	    }
	    try {
		result = dialogTask.get().intValue() ;
	    } catch (InterruptedException e) {
		e.printStackTrace();
	    } catch (ExecutionException e) {
		e.printStackTrace();
	    }
	}
	if (result == JOptionPane.YES_OPTION) {
	    return btlo.setProxy("localhost:8080") ;
	} 
	return btlo ;
    }

    private void getDataHelper() {
	if (dataAvailable()) {
	    getLatestEndMeterReadingAndUpdateCache() ;
	} else {
	    msg(EMPTY ) ;
	    msg("Received " + NO_DATA) ;
	    msg(EMPTY ) ;
	    msg(EMPTY ) ;
	}
    }
    /**
     * @param wd  
     */
    void getData() {
	ValuesForDate values = null ;
	getDataHelper() ;
	String dateString ;

//	/*
//	 * Need to compare variable date of type LocalDate
//	 * with variable cachedDate of type LocalDate,
//	 * using cache lock cacheLock to synchronize.
//	 * 
//	 * If boolean variable cachedValuesValid is true
//	 * and the comparison is equal or after, then
//	 * get the cached meter reading from the long variable
//	 * cachedMeterReading.
//	 * 
//	 */
	synchronized (cacheLock) {
	    cachedValuesUsed  = false ;
	    if (cachedValuesValid && 
		    (date.isEqual(cachedDate) || date.isAfter(cachedDate))
		    ) {
		cachedValuesUsed = true ;

		synchronized (lock) {
		    startRead = (int) cachedMeterReading;
		    dataValid = true ;
		    dateString = date.format(DATE_PATTERN) ;
		    if (date.isAfter(cachedDate)) {
			//
			//
			//  This next line is a MAJOR design decision
			//  to change the date of this object to the cached
			//  date despite this object having been created 
			//  with a different date.
			//
			//
			date = cachedDate ;
			//
			//
			//
			dateChanged = true ;
		    }
		    values = new ValuesForDate.Builder()
		    .success(true)
		    .date(dateString) 
		    .startRead(Integer.toString(startRead))
		    .endRead(Integer.toString(startRead))
		    .consumption("0")
		    .build() ;
		} // End of synchronized on lock.
	    } else {
		synchronized (lock) {
		    dateString = date.format(DATE_PATTERN) ;
		}  // End of synchronized on lock.
	    }
	}  // End of synchronized on cacheLock.

	//
	// Check that there really is data.
	//

	//
	// First, check that the data was properly accessed.
	//

	//
	// Second, check that the server is up.
	//


	/*
	 * ***********************************************************
	 */
	String dateWantedString = 
		((date.getMonthValue()<10)?"0":"") +
		Integer.toString(date.getMonthValue()) +
		"/" +
		((date.getDayOfMonth()<10)?"0":"") +
		Integer.toString(date.getDayOfMonth()) + 
		"/" +
		Integer.toString(date.getYear()) ;
	
	if (values == null) {
	    values = getAllValuesForDate(dateWantedString) ;
	}
	float startReadFloat = Float.parseFloat(values.getStartRead()) ;
	if (DEBUG_SHOW_MESSAGES) {
	    msg("vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv") ;
	    msg("") ;
	    msg("Here is the data in ValuesForDate values:") ;
	    msg("") ;
	    msg("Date           is " + values.getDate()) ;
	    msg("Success status is " + values.isSuccess()) ;
	    msg("Consumption    is " + values.getConsumption()) ;
	    msg("Start reading  is " + values.getStartRead()) ;
	    msg("End   reading  is " + values.getEndRead()) ;
	    msg("") ;
	    msg("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^") ;
	    msg("") ;
	}
	/*
	 * ***********************************************************
	 */

	    synchronized (cacheLock) {
		if (!cachedValuesUsed) {
		    synchronized (lock) {
			startRead = (int) startReadFloat;
			dataValid = true;
		    }
		}
	    }
    }  // End of getData

    /**
     * @param wd The browser being used. 
     */
    private void logout(Browser wd) {
	//
	// Logging out appears to be UNNECESSARY.
	//
	// Closing the browser and closing any
	// BrowserContext_s was eliminated on
	// 17 Sep 2023 by Ian Shef (IBS)
	// because these are used for getting
	// any subsequent data and will be closed
	// automatically when the program exits.
	//
	
	// Close any BrowserContext_s first.
//	for (BrowserContext bc: wd.contexts()) {
//	    bc.close() ;
//	}

	// Now, close the browser.
//	wd.close() ;
    }

    @SuppressWarnings("synthetic-access")
    private void invoke() {
	SmartMeterTexasDataCollectorHelper.SINGLE_INSTANCE.login() ;
	
//	Context c = saveContextAndHideMessages() ;

	waitForFirstPageAfterLogin() ;
	//
	// getData() is unnecessary because waitForFirstPageAfterLogin
	// calls getData
	//
	
	//
	// logout(...) is a do-nothing currently (17 Sep 2023  Ian Shef (IBS)
	//
	logout(
		SmartMeterTexasDataCollectorHelper.SINGLE_INSTANCE.getBrowser()
		) ;
    }
    
    @SuppressWarnings("synthetic-access")
    private boolean dataAvailable() {
	Page page =
		SmartMeterTexasDataCollectorHelper.SINGLE_INSTANCE.getPage() ;
	if (DEBUG_SHOW_MESSAGES) {
	    msg("page.getByText(NO_DATA).count() in " + 
		    Util.getCallerMethodName() + " is " +
		    page.getByText(NO_DATA).count() + ".") ;
	}
	return (page.getByText(NO_DATA).count() == 0) ;
    }
    
    @SuppressWarnings("synthetic-access")
    private String dataFound() {
	//
	// "we" is WebElement
	//
	
	Page page = SmartMeterTexasDataCollector.
		SmartMeterTexasDataCollectorHelper.SINGLE_INSTANCE.getPage() ;
//	Locator intentionalDelay = page.getByText("Daily Meter Reads Table") ;
	//
	// Given to me in an error message by Playwright:
	//
	Locator locSearchFor = page.getByRole
		(
			AriaRole.COLUMNHEADER, 
			new Page
			  .GetByRoleOptions()
			  .setName(SEARCH_FOR)
			)
		.locator("span") ;
	//
	//
	//
	Locator locNoData    = page.getByText(NO_DATA) ;
//	if (intentionalDelay.count() == 0) {
//	    throw new AssertionError("intentionalDelay did not work.") ;
//	}
	if (locSearchFor.count() > 0) {
	    if (locSearchFor.count() > 1 ) {
		    if (DEBUG_SHOW_MESSAGES) {
			msg("locSearchFor.allTextContents() in " + 
				Util.getCallerMethodName() + " is '" +
				locSearchFor.allTextContents() + "'.") ;
		    }
		throw new AssertionError("Too many " + SEARCH_FOR + 
			".  Wanted 1 or 2, got " + locSearchFor.count() + "." ) ;
	    }
	    //
	    // Here IFF locSearchFor.count() == 1
	    //
	    String locSearchForText = locSearchFor.textContent() ;
	    if (DEBUG_SHOW_MESSAGES) {
		msg("locSearchFor.textContent() in " + 
			Util.getCallerMethodName() + " is '" +
			locSearchForText + "'.") ;
	    }
	    return locSearchForText ;
	}
	
	if (locNoData.count() > 0) {
	    msg(EMPTY) ;
	    msg("*** NO DATA IS AVAILABLE. ***") ;
	    msg(EMPTY) ;
	    
	}
	return null ;
    }

    /**
     * @param date1
     */
    @SuppressWarnings("synthetic-access")
    private ValuesForDate getAllValuesForDate(String date1) {
	/*
	 * The date1 String xx/yy/zzzz
	 * must be a two-digit month number (1 as 01, etc.) in 
	 * the range 01 through 12,
	 * followed by a slash, followed by
	 * a two-digit day number (1 as 01, etc.)in the range 01 through 31,
	 * followed by a slash, followed by
	 * a four-digit year number.
	 * 
	 * Partially verify the date String: 
	 */
	if (date1 == null) {
	    throw new AssertionError(
		    "Null date in " + Util.getCallerMethodName()
		    ) ;
	}
	if ( ! (
		date1.length() == 10 &&
		Character.isDigit(date1.charAt(0)) &&
		Character.isDigit(date1.charAt(1)) &&
		date1.charAt(2) == '/' &&
		Character.isDigit(date1.charAt(3)) &&
		Character.isDigit(date1.charAt(4)) &&
		date1.charAt(5) == '/' &&
		Character.isDigit(date1.charAt(6)) &&
		Character.isDigit(date1.charAt(7)) &&
		Character.isDigit(date1.charAt(8)) &&
		Character.isDigit(date1.charAt(9))
		) ) {
	    throw new AssertionError(
		    "Bad date " 
			    + date1 
			    + " in " 
			    + Util.getCallerMethodName()
		    ) ;
	}
	try {
	    LocalDate.parse( date1, DATE_PATTERN ) ;
	} 
	catch (DateTimeParseException e) {
		msg("Exception for bad date " 
			+ date1 
			+ " in " 
			+ Util.getCallerMethodName()) ;
		e.printStackTrace() ;
	}
	ValuesForDate resultForDate ;
	boolean successful = false ;
	String result = "" ;
	Browser browser = SmartMeterTexasDataCollector.
		SmartMeterTexasDataCollectorHelper.
		SINGLE_INSTANCE.getBrowser() ;
	Page page = SmartMeterTexasDataCollector.
		SmartMeterTexasDataCollectorHelper.
		SINGLE_INSTANCE.getPage() ;
	if ((browser == null) || (page == null) ) {
	    msg("Null browser or page in " + 
		    Util.getCallerMethodName()) ;
	    return new ValuesForDate.Builder()
		    .success(false)
		    .date(EMPTY) 
		    .startRead(EMPTY)
		    .endRead(EMPTY)
		    .consumption(EMPTY)
		    .build() ;
	}
	//
	// Set Start Date and End Date
	//
	Locator element ;
	element = page.getByLabel("Start date") ;
	element.fill(date1) ;
	element.press("Enter") ; // Remove pop-up calendar month.
	element = page.getByLabel("End date") ;
	element.fill(date1) ;
	element.press("Enter") ; // Remove pop-up calendar month.
	element = page.getByLabel("Report Type") ;
	element.selectOption("Daily Meter Reads") ;
	if (DEBUG_SHOW_MESSAGES) {
		msg("We should now be at the page with daily data.") ;
	}

	//
	//  Results here.
	//
	
	String results[] = {"", "", "", "", ""} ;
	
	if (dataAvailable()) {
	    for (int i = 0; i < DATA_RETRY_LIMIT; i++) {
		result = dataFound() ;
		if (result != null) break ;
		sleepMillis(DATA_RETRY_MILLIS) ;
	    } 
	}
	if (result == null) {
	    msg(EMPTY) ;
	    msg("Could not find data after " + 
		    DATA_RETRY_LIMIT + " tries and after " + 
		    (DATA_RETRY_LIMIT*DATA_RETRY_MILLIS) + 
		    " milliseconds for date: " + date1 + ".") ;
	    msg(EMPTY) ;
	    System.exit(-1) ;
	}
	//
	// The next line eliminates a compiler warning.
	// Because of System.exit(...) above, this line
	// cannot be reached if result==null, but
	// the compiler does not know that
	// System.exit(...) never returns.
	//
	if (result == null) result = "" ;
	//
	//
	//
	result = page.getByRole(AriaRole.ROW).last().textContent() ;
	result = result.replace("Date", " ") ;
	result = result.replace("Start Read", " ") ;
	result = result.replace("End Read", " ") ;
	result = result.replace("Consumption(Kwh)", " ") ;
	results = result.split("\\s");
	if ( results[1].contentEquals(date1) ) successful = true ;
	if (successful) {
	    msg(EMPTY) ;
	    msg("Date        is " + results[1]) ;
	    msg("Start Read  is " + results[2]) ;
	    msg("End   Read  is " + results[3]) ;
	    msg("Consumption is " + results[4]) ;
	    msg(EMPTY) ;
	    resultForDate = new ValuesForDate.Builder()
		    .success(true)
		    .date(results[1]) 
		    .startRead(results[2])
		    .endRead(results[3])
		    .consumption(results[4])
		    .build() ;
	} else {
	    resultForDate = new ValuesForDate.Builder().success(false).build() ;
	    return resultForDate ;
	}
	element.selectOption("Energy Data 15 Min Interval") ;
	return resultForDate ; 
    }

    @SuppressWarnings("synthetic-access")
    private void getLatestEndMeterReadingAndUpdateCache() {
	final int titleLiteral = 0;
	final int dateLiteral = 1;
	final int dateValue = 2;
	final int timeLiteral = 3;
	final int timeValue = 4;
	final int endMeterReadLiteral = 5;
	final int endMeterReadValue = 6;
	final int lastReadings = endMeterReadValue;
	String[] readings = new String[lastReadings + 1];
	for (int i = 0; i < readings.length; i++)
	    readings[i] = "";
	Runtime rt = new Runtime(); // Gets start time.
	Page page = SmartMeterTexasDataCollector.
		SmartMeterTexasDataCollectorHelper.
		SINGLE_INSTANCE.getPage() ;
	Locator select = page
		.locator("xpath=//div[@class='last-meter-reading']")
		.locator("xpath=//div");
	List<String> strings = select.allTextContents();
	if (DEBUG_SHOW_MESSAGES) {
	    msg("===========================================================");
	    for (String string : strings) {
		msg(string);
		msg("-------------------------------------------------------");
	    }
	    msg("===========================================================");
	}
	readings[titleLiteral] = strings.get(0);
	readings[dateLiteral] = strings.get(3);
	readings[dateValue] = strings.get(4);
	readings[timeLiteral] = strings.get(6);
	readings[timeValue] = strings.get(7);
	readings[endMeterReadLiteral] = strings.get(9);
	readings[endMeterReadValue] = strings.get(10);

	if (DEBUG_SHOW_MESSAGES) {
	    msg("... done finding.  Took " + rt.measurement() / 1000.0
		    + " seconds.");
	}

	if (!readings[titleLiteral].contains("Latest End of Day Read")) {
	    throw new Error("Latest End of Day Read has " + "wrong title of "
		    + readings[titleLiteral] + ".");
	}
	if (!readings[dateLiteral].contains("Date")) {
	    throw new Error("Latest End of Day Read has wrong date subtitle of "
		    + readings[dateLiteral] + ".");
	}
	if (!readings[timeLiteral].contains("Time")) {
	    throw new Error("Latest End of Day Read has wrong time subtitle of "
		    + readings[timeLiteral] + ".");
	}
	if (!readings[timeValue].contains("00:00:00")) {
	    throw new Error("Latest End of Day Read has wrong time value of "
		    + readings[timeValue] + ".");
	}
	if (!readings[endMeterReadLiteral].contains("Meter Read")) {
	    throw new Error("Latest End of Day Read "
		    + "has wrong meter read subtitle of "
		    + readings[endMeterReadLiteral] + ".");
	}

	LocalDate startDate = getLatestStartDate(readings[dateValue]);
	long startReading = getLatestStartRead(readings[endMeterReadValue]);
	synchronized (cacheLock) {
	    cachedDate = startDate;
	    cachedMeterReading = startReading;
	    cachedValuesValid = true;
	}
	if (DEBUG_SHOW_MESSAGES) {
	    msg("Latest Start Date of " + startDate.toString()
		    + " has reading of " + startReading + ".");
	}
    }

    private LocalDate getLatestStartDate(String dateIn) {
	final char FSLASH = '/' ;
	if ((dateIn.charAt(2) == FSLASH) && (dateIn.charAt(5) == FSLASH)) {
	    String yearString = dateIn.substring(6, 10) ;
	    String monthString = dateIn.substring(0, 2) ;
	    String dayString = dateIn.substring(3, 5) ;
	    int year  = Integer.parseInt(yearString) ;
	    int month = Integer.parseInt(monthString) ;
	    int day   = Integer.parseInt(dayString) ; 
	    return LocalDate.of(year, month, day).plusDays(1) ;
	}
	throw new AssertionError(
		"Bad date string of " +
		dateIn +
		" in getLatestStartDate."
		) ;
    }

    private long getLatestStartRead(String in) {
	return (long)Float.parseFloat(in) ;
    }
    
    public static Context saveContextAndHideMessages() {
	Context c = new Context() ;
	c.ps = System.err ;
	System.setErr(new PrintStream(new OutputStream() {
	    @Override
	    public void write(int b) {
		/* Intentionally do nothing. */
	    } } )) ;
	return c ;
    }
    public static void restoreContextAndUnhideMessages(Context c) {
	System.setErr(c.ps) ;
    }

    /**
     * @return the date
     */
    @Override
    public LocalDate getDate() {
	return date;
    }

    /**
     * @return the startRead
     */
    @Override
    public int getStartRead() {
	int value ;
	boolean dv ;
	synchronized(lock) {
	    value = startRead ;
	    dv = dataValid ;
	}
	if (!dv) {
	    invoke() ;
	    value = startRead ;
	}
	return value;
    }

    /**
     * @return whether the data is valid
     */
    public boolean isDataValid() {
	boolean value ;
	synchronized(lock) {
	    value = dataValid ;
	}
	return value;
    }

    /**
     * @return the dateChanged
     */
    @Override
    public boolean isDateChanged() {
	boolean dc ;
	synchronized (lock) {
	    dc = dateChanged ;
	}
	return dc ;
    }
    
    @Override
    public int getGreenStart() {
	return 500 ; // Ian Shef ibs
    }
    
    @Override
    public int getGreenEnd() {
	return 1000 ; //  Ian Shef  ibs
    }
    
    static class AccountInfo {
	private static final Info info = new Info() ;
	
	public int getGreenStart() {
	    return info.getGreenStart() ;
	}
	
	public int getGreenEnd() {
	    return info.getGreenEnd() ;
	}

    }
    
    @SuppressWarnings("synthetic-access")
    private void waitForFirstPageAfterLogin() {
	
	int tries = 1;
	Page page = SmartMeterTexasDataCollector.
		SmartMeterTexasDataCollectorHelper.
		SINGLE_INSTANCE.getPage() ;
	do {
	    int correctTitleTimesInARow = 0;
	    int titleTriesRemaining;
	    for (
		    titleTriesRemaining = 12; 
		    titleTriesRemaining > 0; 
		    titleTriesRemaining--) {
		if (page.title().startsWith(TITLE)) {
		    correctTitleTimesInARow++;
		    if (correctTitleTimesInARow == 3)
			break;
		} else {
		    correctTitleTimesInARow = 0;
		    msg("Page title is: '" + page.title() + "'");
		}
		sleepMillis(1000);
	    }
	    if (titleTriesRemaining == 0) {
		msg("Failed to get title starting with " + TITLE + ".") ;
		Browser browser = SmartMeterTexasDataCollector.
			SmartMeterTexasDataCollectorHelper.
			SINGLE_INSTANCE.getBrowser() ;
		browser.close() ;
	    } else {
		getData();
		break;
	    }
	} while (tries++ < RETRY_LIMIT);
    }

    /**
     * 
     */
    private static void sleepMillis(int sleepMilliseconds) {
	try {
	    Thread.sleep(sleepMilliseconds) ;
	} catch (InterruptedException e1) {
	    Thread.currentThread().interrupt() ;
	    e1.printStackTrace();
	}
    }

    static class Context {
	PrintStream ps ;
    }
    
    static class Runtime {
	private long startTime = System.currentTimeMillis() ;
	private long endTime ;
	
	public long measurement() {
	    makeMeasurement() ;
	    return getMeasurement() ;
	}
	
	public void makeMeasurement() {
	    endTime = System.currentTimeMillis() ;
	}
	
	public long getMeasurement() {
	    return endTime - startTime ;
	}
    }
    static class ValuesForDate {
	private boolean success = false ;
	private String date = " 01/01/00" ;
	private String startRead = "0" ;
	private String endRead = "0" ;
	private String consumption = "0" ;
	
	//
	// No accessible no-argument constructor.
	//
	private ValuesForDate() {}
	
	private ValuesForDate(Builder b) {
	    success     = b.success ;
	    date        = b.date ;
	    startRead   = b.startRead ;
	    endRead     = b.endRead ;
	    consumption = b.consumption ;
	}
	
	    public static class Builder {
		// Required parameters
		boolean success ;
		String date ;
		String startRead ;
		String endRead ;
		String consumption ;
		
		// Optional parameters initialized to default values - NONE
		
		public Builder success(boolean s) {
		    success = s ;
		    return this ;
		}
		
		public Builder date(String dateOfValuesForDate) {
		    date = dateOfValuesForDate ;  
		    return this ;
		}
		
		public Builder startRead(String startValue) {
		    startRead = startValue ;
		    return this ;
		}
		
		public Builder endRead(String endValue) {
		    endRead = endValue ;
		    return this ;
		}
		
		public Builder consumption(String consumptionValue) {
		    consumption = consumptionValue ;
		    return this ;
		}
		
		@SuppressWarnings("synthetic-access")
		public ValuesForDate build() {
		    return new ValuesForDate(this) ;
		}
	    }

	    /**
	     * @return the success
	     */
	    public boolean isSuccess() {
	        return success;
	    }

	    /**
	     * @return the date
	     */
	    public String getDate() {
	        return date;
	    }

	    /**
	     * @return the startRead
	     */
	    public String getStartRead() {
	        return startRead;
	    }

	    /**
	     * @return the endRead
	     */
	    public String getEndRead() {
	        return endRead;
	    }

	    /**
	     * @return the consumption
	     */
	    public String getConsumption() {
	        return consumption;
	    }

    }
    //
    // The following is set up to have a single instance of the class
    // (aka singleton)
    //
    // This is called "...the best way to implement a singleton"
    // in 
    // _Effective Java Second Edition_
    // by 
    // Joshua Bloch,
    // published by Addison-Wesley, September 2008,
    // page 18
    //
    private enum SmartMeterTexasDataCollectorHelper {

	SINGLE_INSTANCE ;
	
	private static Playwright playwright;
	private static Browser browser;
	private static BrowserType browserType;
	private static Page page;
	private static BrowserContext browserContext;
	private static boolean ready = false;

	//
	// No publically-accessible constructor.
	//
	private SmartMeterTexasDataCollectorHelper() {
//	    login();
	}

	private synchronized void login() {
	    if (!ready) {
		loginHelper();
	    }
	}

	/**
	 * @throws AssertionError
	 */
	@SuppressWarnings("synthetic-access")
	private void loginHelper() {

	    //
	    //
	    // Eliminate error messages:
	    // Gtk-Message: <timestamp>: Failed to load module
	    // "canberra-gtk-module"
	    //
	    // by doing in a terminal:
	    // sudo apt-get install libcanberra-gtk-module
	    //

	    playwright = Playwright.create();

	    if (DEBUG_SHOW_MESSAGES) {
		System.out.println("Using " + browserName
			+ " to go to the login page at " + GET_URL + ".");
	    }
	    BrowserType.LaunchOptions lo = new BrowserType.LaunchOptions()
		    .setHeadless(!DEBUG_SHOW_BROWSER);
	    lo = DEBUG_SLOW_BROWSER ? lo.setSlowMo(BROWSER_DELAY_MILLIS) : lo;
	    if (DEBUG_USE_PROXY) {
		lo = useProxy(lo);
	    }
	    switch (browserName) {
	    case FIREFOX:
		browserType = playwright.firefox() ;
		break;
	    case CHROMIUM:
		browserType = playwright.chromium() ;
		break;
	    default:
		throw new AssertionError(
			"Not prepared for browser " + browserName.toString());
	    }

	    browser = browserType.launch(lo);
	    page = browser.newPage();
	    //
	    //  The method page.navigate(GET_URL) will throw an error if:
	    //
	    // there's an SSL error (e.g. in case of self-signed certificates).
	    // target URL is invalid.
	    // the timeout is exceeded during navigation.    <<<----------------
	    // the remote server does not respond or is unreachable.
	    // the main resource failed to load.
	    //
	    // ... so try to catch the timeout error and try again.
	    //

	    boolean tryAgain = true ;
	    while (tryAgain) {
		try {
		    page.navigate(GET_URL);
		    tryAgain = false ; // Can't get here if exception is thrown.
		} catch (com.microsoft.playwright.TimeoutError e) {
		    if (DEBUG_SHOW_MESSAGES) {
			System.out.print("Timed out going to the login page,") ;
			System.out.println(" trying again.") ;
		    }
		    e.printStackTrace();
		} 
	    }
	    
	    if (DEBUG_SHOW_MESSAGES) {
		System.out.println("Went to the login page.");
		System.out.println("Title is " + page.title() + ".");
//		                             Login - Smart Meter Texas
	    }
	    
	    browserContext = page.context();
	    int bcs = browser.contexts().size();
	    if (bcs != 1)
		throw new AssertionError(
			"Wrong number of SmartMeterTexasDataCollectorHelper = "
				+ bcs + ", should be one.");

	    //
	    // <<<<<<<<<<<<<<<<<<<<<<<<<<<<< UNTESTED BELOW
	    // >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
	    //
	    Locator locMaint = page.getByText(MAINTENANCE);
	    if (DEBUG_SHOW_MESSAGES) {
		System.out.println(
			"Maintenance Locator locMaint is " + locMaint
			+ " with count of " + locMaint.count() + ".");
	    }
	    if (locMaint.count() > 0) {
		System.out.println() ;
		System.out.println(MAINTENANCE) ;
		System.out.println() ;
		System.out.println("TRY AGAIN LATER !") ;
		System.out.println() ;
		System.exit(-5);
	    }
	    //
	    // <<<<<<<<<<<<<<<<<<<<<<<<<<<<< UNTESTED ABOVE
	    // >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
	    //

	    // Interact with login form

	    String u = (String) Info.PreferencesEnum.keyUserID.getStoredValue();
	    String p = (String) Info.PreferencesEnum.keyPassword
		    .getStoredValue();
	    if (DEBUG_SHOW_MESSAGES) {
		System.out.println("User ID  is " + u + ".");
	    }

	    Locator loc = page.getByLabel("User ID:");
	    loc.fill(u);

	    if (DEBUG_SHOW_MESSAGES) {
		System.out.println("Password is " + p + ".");
	    }

	    loc = page.getByLabel("password");
	    loc.fill(p);

	    if (DEBUG_SHOW_MESSAGES) {
		System.out.println("Filled in password, now going to submit.");
		System.out.println("Title is currently " + page.title() + ".");
//	                                            Login - Smart Meter Texas
	    }

	    //
	    // Submit the form
	    //
	    loc.press("Tab"); // Goes to "Remember Me"
	    loc.press("Tab"); // Goes to "Login" button.
	    loc.press("Enter"); // Clicks on "Login" button.

	    page.waitForCondition(
		    () -> (page.title()
			    .equals("Dashboard - Smart Meter Texas")),
		    new Page.WaitForConditionOptions().setTimeout(5 * 1_000));

	    if (DEBUG_SHOW_MESSAGES) {
		System.out.println("Title is now " + page.title() + ".");
//	                                     Dashboard - Smart Meter Texas
	    }

	    if (DEBUG_SHOW_MESSAGES) {
		if (page.title().equals("Dashboard - Smart Meter Texas")) {
		    System.out.println(
			    "We are now logged in and at the data page."
			    ) ;
		} else {
		    throw new AssertionError("We are NOT at the data page... "
			    + "something went wrong.");
		}
	    }
	    ready = true;
	}

	/**
	 * @return the browser
	 */
	private final synchronized Browser getBrowser() {
	    if (isReady()) {
		return browser;
	    }
	    return null;
	}

	/**
	 * @return the pagE
	 */
	private final synchronized Page getPage() {
	    if (isReady()) {
		return page;
	    }
	    return null;
	}

	/**
	 * @return the browserContext
	 */
	@SuppressWarnings("unused")
	private final synchronized BrowserContext getBrowserContext() {
	    if (isReady()) {
		return browserContext;
	    }
	    return null;
	}

	/**
	 * @return the ready
	 */
	private static final boolean isReady() {
	    return ready;
	}

    }
} // end of class SmartMeterTexasDataCollector

enum BrowserName {
    CHROMIUM,
    FIREFOX,
    WEBKIT
}
