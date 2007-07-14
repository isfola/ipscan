/**
 * This file is a part of Angry IP Scanner source code,
 * see http://www.azib.net/ for more information.
 * Licensed under GPLv2.
 */
package net.azib.ipscan.core;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.azib.ipscan.config.Labels;
import net.azib.ipscan.core.state.ScanningState;
import net.azib.ipscan.core.state.StateMachine;
import net.azib.ipscan.core.state.StateTransitionListener;
import net.azib.ipscan.feeders.Feeder;
import net.azib.ipscan.fetchers.Fetcher;
import net.azib.ipscan.fetchers.FetcherRegistry;

/**
 * The holder of scanning results.
 *
 * @author anton
 */
public class ScanningResultList implements Iterable<ScanningResult> {
	
	private static final int RESULT_LIST_INITIAL_SIZE = 1024;
	
	private FetcherRegistry fetcherRegistry;
	// selected fetchers are cached here, because they may be changed in the registry already
	private List<Fetcher> selectedFetchers;
	
	private List<ScanningResult> resultList = new ArrayList<ScanningResult>(RESULT_LIST_INITIAL_SIZE);
	private Map<InetAddress, Integer> resultIndexes = new HashMap<InetAddress, Integer>(RESULT_LIST_INITIAL_SIZE);
		
	/** Feeder information that was used for this scan */
	private String feederInfo;

	private ScanInfo info;
	
	private ResultsComparator resultsComparator = new ResultsComparator();
	
	public ScanningResultList(FetcherRegistry fetcherRegistry) {
		this.fetcherRegistry = fetcherRegistry;
	}
	
	public ScanningResultList(FetcherRegistry fetcherRegistry, StateMachine stateMachine) {
		this(fetcherRegistry);
		stateMachine.addTransitionListener(new StopScanningListener());
	}

	/**
	 * @return selected fetchers that were used for the last scan
	 * Note: they may be different from {@link FetcherRegistry#getSelectedFetchers()}
	 */
	public List<Fetcher> getFetchers() {
		return selectedFetchers;
	}
		
	/**
	 * @return true if scanning results are available and can be used
	 */
	public boolean areResultsAvailable() {
		return !resultList.isEmpty();
	}
	
	/**
	 * @return feeder information that was used for the last scan
	 */
	public String getFeederInfo() {
		return feederInfo;
	}
	
	/**
	 * Creates the new results holder for particular address or returns an existing one.
	 * @param address
	 * @return pre-initialized empty ScanningResult
	 */
	public synchronized ScanningResult createResult(InetAddress address) {
		Integer index = resultIndexes.get(address);
		if (index == null) {
			info.numScanned++;
			return new ScanningResult(address, fetcherRegistry.getSelectedFetchers().size());
		}
		return resultList.get(index);
	}

	/**
	 * Registers the provided results holder at the specified index in this list.
	 * This index will later be used to retrieve the result when redrawing items.
	 * TODO: index parameter is not really needed here - add method with index will not work with sparce lists anyway
	 * @param index
	 * @param result
	 */
	public synchronized void registerAtIndex(int index, ScanningResult result) {
		if (resultIndexes.put(result.getAddress(), index) != null)
			throw new IllegalStateException(result.getAddress() + " is already registered in the list");
		
		resultList.add(index, result);
		
		// update statistics
		if (result.getType() == ScanningSubject.RESULT_TYPE_ALIVE) {
			info.numAlive++;
		}
		else if (result.getType() == ScanningSubject.RESULT_TYPE_ADDITIONAL_INFO) {
			info.numAlive++;
			info.numWithPorts++;
		}
	}
	
	/**
	 * @return true if the provided result holder exists in the list.
	 */
	public synchronized boolean isRegistered(ScanningResult result) {
		return resultIndexes.containsKey(result.getAddress());
	}	

	/**
	 * @return the index of the result in the list, if it is registered
	 */
	public synchronized int getIndex(ScanningResult result) {
		return resultIndexes.get(result.getAddress());
	}

	/**
	 * Returns all results for a particular IP address as a String.
	 * This is used in showing the IP Details dialog box.
	 * 
	 * @param index
	 * @return human-friendly text representation of results
	 */
	public synchronized String getResultAsString(int index) {
		// cross-platform newline :-)
		String newLine = System.getProperty("line.separator");
		
		ScanningResult scanningResult = resultList.get(index);
		StringBuffer details = new StringBuffer(1024);
		Iterator<Object> iterator = scanningResult.getValues().iterator();
		for (int i = 0; iterator.hasNext(); i++) {
			String fetcherName = Labels.getLabel(selectedFetchers.get(i).getLabel());
			details.append(fetcherName).append(":\t");
			Object value = iterator.next(); 
			details.append(value != null ? value : "");
			details.append(newLine);
		}
		return details.toString();	
	}

	/**
	 * Clears previous scanning results, prepares for a new scan.
	 */
	public synchronized void clear() {
		// clear the results
		resultList.clear();
		resultIndexes.clear();
		selectedFetchers = null;
	}

	/**
	 * Clears previous scanning results, prepares for a new scan.
	 * @param feeder the feeder that will be used for the scan
	 */
	public synchronized void initNewScan(Feeder feeder) {
		// first clear
		clear();
		// reload currently selected fetchers
		selectedFetchers = new ArrayList<Fetcher>(fetcherRegistry.getSelectedFetchers());		
		// store feeder info for later
		this.feederInfo = feeder.getInfo();
		// recreate info
		this.info = new ScanInfo();
	}

	/**
	 * @return ScanInfo instance that contains some additional information about the last scan.
	 */
	public ScanInfo getScanInfo() {
		return info;
	}
		
	/**
	 * @return an Iterator of scanning results
	 * 
	 * Note: the returned Iterator is not synchronized
	 */
	public synchronized Iterator<ScanningResult> iterator() {
		return resultList.iterator();
	}

	/**
	 * @param index
	 * @return the results of the IP adress, corresponding to an index
	 */
	public synchronized ScanningResult getResult(int index) {
		return resultList.get(index);
	}

	/**
	 * Removes the elements by the provided indices
	 * @param indices a sorted list of indices to remove
	 */
	public synchronized void remove(int[] indices) {
		// this rebuild is faster then a number of calls to remove()
		// however, a further speedup may be obtained by using a Set instead of binarySearch()
		List<ScanningResult> newList = new ArrayList<ScanningResult>(RESULT_LIST_INITIAL_SIZE);
		Map<InetAddress, Integer> newMap = new HashMap<InetAddress, Integer>(RESULT_LIST_INITIAL_SIZE);
		for (int i = 0; i < resultList.size(); i++) {
			if (Arrays.binarySearch(indices, i) < 0) {
				newList.add(resultList.get(i));
				newMap.put(resultList.get(i).getAddress(), newList.size()-1);
			}
		}
		resultList = newList;
		resultIndexes = newMap;
	}
	
	/**
	 * Sorts by the specified column index.
	 * @param columnIndex
	 */
	public synchronized void sort(int columnIndex) {
		resultsComparator.index = columnIndex;
		Collections.sort(resultList, resultsComparator);
		// now rebuild indexes
		resultIndexes = new HashMap<InetAddress, Integer>(RESULT_LIST_INITIAL_SIZE);
		for (int i = 0; i < resultList.size(); i++) {
			resultIndexes.put(resultList.get(i).getAddress(), i);
		}
	}
	
	/**
	 * Finds the text in the result list. 
	 * @param text the text to find
	 * @param startIndex the element to start from
	 * @return the index of found element, or -1
	 */
	public int findText(String text, int startIndex) {
		for (int i = startIndex; i < resultList.size(); i++) {
			ScanningResult scanningResult = getResult(i);
			
			for (Object value : scanningResult.getValues()) {				
				// TODO: case-insensitive search
				if (value != null && value.toString().indexOf(text) >= 0) {						
					return i;
				}
			}
		}
		// not found
		return -1;
	}
	
	/**
	 * Additional information about the last scan
	 */
	public static class ScanInfo {
		
		boolean scanFinished;

		long startTime = System.currentTimeMillis();
		long endTime;
		int numScanned;
		int numAlive;
		int numWithPorts;	
		
		/**
		 * @return total scan time, in milliseconds.
		 * If scan is not finished yet, then shows the time from start until now.
		 */
		public long getScanTime() {
			long endTime = this.endTime;
			if (endTime == 0)
				endTime = System.currentTimeMillis();
			return endTime - startTime;
		}
		
		/**
		 * @return total number of scanned hosts
		 */
		public int getHostCount() {
			return numScanned;
		}
		
		/**
		 * @return number of alive hosts
		 */
		public int getAliveCount() {
			return numAlive;
		}
		
		/**
		 * @return number of hosts with some scanned ports open
		 */
		public int getWithPortsCount() {
			return numWithPorts;
		}

		/**
		 * @return true if the scan is comepleted (not aborted) 
		 */
		public boolean isFinished() {
			return scanFinished;
		}
	}
	
	class StopScanningListener implements StateTransitionListener {
		public void transitionTo(ScanningState state) {
			// transition to IDLE means complete stop
			if (state == ScanningState.IDLE) {
				info.endTime = System.currentTimeMillis();
				info.scanFinished = true;
			}
		}
	}
	
	static class ResultsComparator implements Comparator<ScanningResult> {
		
		private int index;

		public int compare(ScanningResult r1, ScanningResult r2) {
			Object val1 = r1.getValues().get(index);
			Object val2 = r2.getValues().get(index);
			
			return val1.toString().compareTo(val2.toString());
		}
	}
}