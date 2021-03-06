/*
 * Copyright (c) 2016 Brigham Young University
 *
 * This file is part of the BYU RapidSmith Tools.
 *
 * BYU RapidSmith Tools is free software: you may redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * BYU RapidSmith Tools is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * A copy of the GNU General Public License is included with the BYU
 * RapidSmith Tools. It can be found at doc/LICENSE.GPL3.TXT. You may
 * also get a copy of the license at <http://www.gnu.org/licenses/>.
 */

package edu.byu.ece.rapidSmith.examples.placerDemo;


import edu.byu.ece.rapidSmith.design.NetType;
import edu.byu.ece.rapidSmith.design.subsite.Cell;
import edu.byu.ece.rapidSmith.design.subsite.CellDesign;
import edu.byu.ece.rapidSmith.design.subsite.CellNet;
import edu.byu.ece.rapidSmith.design.subsite.CellPin;
import edu.byu.ece.rapidSmith.device.Device;
import edu.byu.ece.rapidSmith.device.Site;
import edu.byu.ece.rapidSmith.device.SiteType;
import edu.byu.ece.rapidSmith.device.families.Artix7;
import edu.byu.ece.rapidSmith.interfaces.vivado.XdcPlacementInterface;
import edu.byu.ece.rapidSmith.util.MessageGenerator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.*;

public class SimulatedAnnealingPlacer {
	
	private final CellDesign design;
	private final Device device;
	private final ArrayList<SiteCluster> placeableSiteClusters;
	private final ArrayList<SiteCluster> allSiteClusters;
	private HashMap<Site, SiteCluster> sitenameToClusterMap;
	private final int[] netToCostMap;
	
	// Update these as you like to see the annealing aspects of the placer
	// The last element in the array should always be 0!
	private final double[] checkpointTimes = {.30, .10, .05, .01, .005, 0};
	private int currentCheckpointTime = 0; 
	private BufferedWriter vivadoOut = null;
	private boolean viewCheckpoints = false; 
	private String placementXdc = null;
	
	private HashMap<SiteType, List<Site>> siteTypeMap = new HashMap<>();
	//placement cost variables
	private int cost; 
	
	/**
	 * Constructor
	 * @param device
	 * @param design
	 */
	public SimulatedAnnealingPlacer(Device device, CellDesign design) {
		this.design = design;
		this.device = device;
		this.placeableSiteClusters = new ArrayList<>();
		this.allSiteClusters = new ArrayList<>();
		this.sitenameToClusterMap = new HashMap<>();
		this.netToCostMap = new int[design.getNets().size()];
		this.siteTypeMap = new HashMap<>();
		this.buildSiteClusters();
		
		// unroute all intrasite nets to prevent LUT routethroughs from being inserted
		design.getNets().forEach(CellNet::unrouteFull);
	}
	
	public void setVivadoOutputStream(BufferedWriter out, String checkpoint) {
		this.vivadoOut = out;
		this.placementXdc = checkpoint + "placement.xdc";
		this.viewCheckpoints = true; 
	}
	
	/**
	 * Takes the current design, and build the corresponding site clusters for the placement algorithm
	 */
	private void buildSiteClusters() {
		//build all carry chain clusters
		HashMap<Site, SiteCluster> siteToCluster = buildCarryChainClusters(design.getUsedSites());
		//build all DSP carry clusters
		siteToCluster.putAll(this.buildDSPCarryClusters());
		
		//create site cluster objects that aren't carry chains (SLICE or DSP)
		for (Site site : design.getUsedSites()) {
			
			if (!this.sitenameToClusterMap.containsKey(site)) {
				SiteCluster sc = createSiteCluster(site); 
				
				// don't place IOB, BUFG, and PLL cells...we leave this to Vivado since there 
				// are specific rules about dedicated clocking resources that I am unsure of
				if (!isPad(site) && !isBUFG(site) && !isPLL(site)) {
					this.placeableSiteClusters.add(sc);
					this.sitenameToClusterMap.put(site, sc);
				}
				
				this.allSiteClusters.add(sc);
				siteToCluster.put(site, sc);
			}
		}
				
		this.buildSiteTypeToCompatibleMap();
		//create an initial placement to work off of
		this.randomizePlacement();
		
		// create the virtual nets of site connections (and initialize the cost of the placement)
		// TODO: remove carry chain nets / dsp48 pout nets from the cost calculation
		for (CellNet net : design.getNets()) {
			//ignore GND, VCC, and BUFG nets, they don't affect placement
			if(!shouldIgnoreNet(net)) {  
				//populate the source information
				VirtualNet vnet = new VirtualNet();
				vnet.setName(net.getName());
				Site sourceSite = net.getSourcePin().getCell().getSite();
				SiteCluster scSource = siteToCluster.get(sourceSite);
			
				//populate the sink information
				ArrayList<SiteCluster> sinks = new ArrayList<>();
				HashSet<Site> usedSinkSites = new HashSet<>();
				int sinkCount = 0;
				for (CellPin sinkpin : net.getSinkPins() ) {
					Site sinkSite = sinkpin.getCell().getSite();
					
					//ignore parts of nets that start in one site and end in the same site
					if (!sourceSite.equals(sinkSite) && !usedSinkSites.contains(sinkSite) ) {
						SiteCluster scSink = siteToCluster.get(sinkpin.getCell().getSite());
						sinks.add(scSink);
						sinkCount++;
						scSink.addnet(vnet);
						usedSinkSites.add(sinkSite);
					}
				}
				
				//Don't add nets that start and end in the same site
				if(sinkCount != 0) {
					vnet.setSource(scSource);
					scSource.addnet(vnet);
					
					//use an array to make calculating net costs faster
					SiteCluster[] netSinks = new SiteCluster[sinkCount];
					
					int i = 0;
					for(SiteCluster sc: sinks)
						netSinks[i++] = sc;
					
					vnet.setSinks(netSinks);
					
					int netCost = vnet.calculateCost();
					this.netToCostMap[vnet.getUniqueID()] =  netCost;
					this.cost += netCost;
				}
			}
		}
		
		for(SiteCluster sc : this.placeableSiteClusters) {
			sc.storeUniqueNets();
		}
		
		System.out.println("Initial Cost: " + this.cost);
		
		unplaceAllCells();
	}
	
	/*
	 * Returns the CIN cell pin of the next DSP cell in a 
	 * carry-connected DSP site
	 */
	private CellPin dspGetNextCarryInCellPin(Cell dsp){
		CellNet acout = dsp.getPin("ACOUT[0]").getNet();
		if (acout != null) {
			if (acout.getSinkPins().size() > 0)
				return acout.getSinkPins().iterator().next();
		}
		
		CellNet bcout = dsp.getPin("BCOUT[0]").getNet();
		if (bcout != null) {
			if(bcout.getSinkPins().size() > 0)
				return bcout.getSinkPins().iterator().next();
		}

		CellNet pcout = dsp.getPin("PCOUT[0]").getNet();
		if (pcout != null) {
			if (pcout.getSinkPins().size() > 0)
				return pcout.getSinkPins().iterator().next();
		}
			
		return null; 
	}
	
	/*
	 * Identifies and creates DSP carry cluster objects.
	 * Only DSP's that are a part of a carry chain used when creating these objects
	 * Regular DSP's are created as regular Site Cluster objects 
	 */
	private HashMap<Site, SiteCluster> buildDSPCarryClusters() {
		ArrayList<Cell> dspCells = new ArrayList<>();
		HashMap<Site, SiteCluster> siteToCluster = new HashMap<>();
		
		//filter out all cells but dsp48 cells...maybe it would be better to walk through all of the sites, and filter out dsp sites...
		//there are much fewer used sites than cells...but we are only doing this once so its not that big of a deal.
		for (Cell c : design.getCells()) {
			if(c.getLibCell().getName().equals("DSP48E1")) 
				dspCells.add(c);
		}
		
		//look for the start of DSP carry chains
		for(Cell dsp : dspCells){
			//check to see if the DSP has nets non-global logic nets connected to any of its CIN pins...if it does than it can't be the start of a carry chain 
			if(!netIsGlobalLogic(dsp.getPin("ACIN[0]").getNet()) || !netIsGlobalLogic(dsp.getPin("BCIN[0]").getNet()) || !netIsGlobalLogic(dsp.getPin("PCIN[0]").getNet()) ) 
				continue;
		
			CellPin carryIn = dspGetNextCarryInCellPin(dsp);
			
			if (carryIn != null) { //this means that the current dsp is the start of a carry chain
				Site site = dsp.getSite();
				System.out.println("Site " + site.getName() + " is the start of a dsp carry chain!");
				DSPCarryCluster start = new DSPCarryCluster(site);
				start.addCell(dsp);
				this.sitenameToClusterMap.put(site, start);
				this.allSiteClusters.add(start);
				this.placeableSiteClusters.add(start);
				siteToCluster.put(site, start);
				
				int height = 1;
				while (carryIn != null) { //get the carry connections
					height++; 
					Cell nextDSP = carryIn.getCell();
					Site nextSite = nextDSP.getSite();
					SiteCluster tmp = new SiteCluster(nextSite);
					tmp.addCell(nextDSP);
					
					this.sitenameToClusterMap.put(nextSite, start);
					siteToCluster.put(nextSite, start);
					
					start.addDependentSite(tmp);
					
					carryIn = dspGetNextCarryInCellPin(nextDSP);
				}
				start.setCarryChainHeight(height);
			}
		}
		return siteToCluster;
	}
	
	/*
	 *	Identifies and build carry chain clusters
	 */
	private HashMap<Site, SiteCluster> buildCarryChainClusters(Collection<Site> sites) {
		HashMap<Site, SiteCluster> siteToCluster = new HashMap<>();
		for (Site site: sites) {
			//finding all starts to carry chains
			try {	
				Cell carry4 = design.getCellAtBel(site.getBel("CARRY4"));			
				CellNet carryInNet = carry4.getPin("CI").getNet();
				CellNet carryOutNet = carry4.getPin("CO[3]").getNet();
				
				if ( netIsGlobalLogic(carryInNet) && netIsDedicatedCarryChain(carryOutNet) ) {
					//System.out.println("Site: " + site.getName() + " is a start of a carry chain!");
					CarryChainCluster start = new CarryChainCluster(site);
					
					for(Cell c: design.getCellsAtSite(site)) 
						start.addCell(c);
					
					this.sitenameToClusterMap.put(site, start);
					this.allSiteClusters.add(start);
					this.placeableSiteClusters.add(start);
					siteToCluster.put(site, start);
					
					int height = 1;
					CellPin carryIn = getNextCarryInCellPin(carryOutNet);
					while (carryIn != null) {
						height++;
						Site nextSite = carryIn.getCell().getSite();
						SiteCluster carryTmp = createSiteCluster(nextSite);
						start.addDependentSite(carryTmp);
						
						this.sitenameToClusterMap.put(nextSite, start);
						siteToCluster.put(nextSite, carryTmp);
						
						//TODO: replace this with carryIn.getCell()...I already have a handle to the next carry4 cell
						carry4 = design.getCellAtBel(nextSite.getBel("CARRY4"));			
						carryOutNet = carry4.getPin("CO[3]").getNet();
						carryIn = getNextCarryInCellPin(carryOutNet);
					}
					
					start.setCarryChainHeight(height);
				}
					
			} catch (NullPointerException e) { 
				//System.out.println("NULL POINTER!");
			} //not the start of a carry chain
			
		}
		return siteToCluster;
	}
	
	
	/*
	 * Helper function used to create the correct cluster object
	 * depending on the Primitive Site parameter 
	 */
	private SiteCluster createSiteCluster(Site site){
		SiteCluster sc;
		
		String sitetype = site.getType().toString();
		if (sitetype.contains("RAMB") || sitetype.contains("FIFO")){
			//System.out.println("BRAM Cluster: " + site.getName());
			sc = new BramCluster(site);
		}
		else {
			sc = new SiteCluster(site);
		}
		
		for(Cell c: design.getCellsAtSite(site)) 
			sc.addCell(c);
		
		return sc;
	}
	
	private void buildSiteTypeToCompatibleMap() {
		//TODO: play with load factor and other parameters of the hash map?
		
		for (SiteCluster sc: this.placeableSiteClusters) {
			SiteType sitetype = sc.getSite().getType();
			if(!siteTypeMap.containsKey(sitetype)) 
				siteTypeMap.put(sitetype, device.getAllCompatibleSites(sitetype));
		}
	}
	
	/*
	 * Check to see if the given net is global logic, or a BUFG (clk) net.
	 * If so, don't include it in our final cost calculation
	 */
	private boolean shouldIgnoreNet(CellNet net){
		return this.netIsGlobalLogic(net) || this.isBufgNet(net);
	}
	
	private boolean isBufgNet(CellNet net) {
		if (net.getSourcePin().getCell().getSite().getType().equals(Artix7.SiteTypes.BUFG)) {
			System.out.println("BUFG Net: " + net.getName());
			return true;
		}
		else {
			for (CellPin cp : net.getSinkPins()) {
				if(cp.getCell().getSite().getType().equals(Artix7.SiteTypes.BUFG)) {// || cp.getBelPin().getName().equals("CE")) {
					System.out.println("BUFG Net: " + net.getName());
					return true;
				}
			}
		}
		
		return false;
		//return net.getSourcePin().getCell().getSite().getType().equals(SiteType.BUFG);
	}
	private boolean netIsGlobalLogic(CellNet net) {
		return net.getType().equals(NetType.VCC) || net.getType().equals(NetType.GND);
	}
	
	/*
	 * Helper function to find carry chains
	 */
	private boolean netIsDedicatedCarryChain (CellNet net) {
		
		for (CellPin cp : net.getSinkPins()) {
			if ( cp.getName().equals("CI") )
				return true;
		}
		
		return false;
	}
	
	/*
	 * Helper function to find carry chains
	 */
	private CellPin getNextCarryInCellPin (CellNet net) {
		if (net != null) {
			for (CellPin cp : net.getSinkPins()) {
				if ( cp.getName().equals("CI") ) {
					return cp;
				}
			}
		}
		
		return null;
	}
	
	
	//code to filter out unwanted sites for placement (we will ignore iob pads and bufgs
	private boolean isPad(Site site){	
		//System.out.println(site.getType());
		return Artix7.IO_SITES.contains(site.getType());
		//return site.getType().toString().startsWith("IOB");		
	}
	
	private boolean isBUFG(Site site) {		
		return site.getType().equals(Artix7.SiteTypes.BUFG);
	}
	private boolean isPLL(Site site) {
		// TODO add PLL_ADV back in
		return site.getType().equals(Artix7.SiteTypes.PLLE2_ADV) /*|| site.getType().equals(Artix7.SiteTypes.PLL_ADV )*/;
	}
	
	/*
	 * Undo all of the current placement information once the design has been intially randomized
	 */
	private void unplaceAllCells() {
		for(Cell c : design.getCells() ) {
			if (c.isPlaced()) // don't unplace GND and VCC cells since they are not placed
				design.unplaceCell(c);
		}			
	}
	
	/**
	 * Computes an initial temperature for the annealing algorithm by doing
	 * 10,000 moves, and averaging the cost over all of those moves.
	 */
	private int calculateInitialTemperature() {
		//do 100 moves and find the average cost change of a move
		Random rn = new Random();
		int total_cost = 0;
		int moves_tested = 0; 
		int size  = this.placeableSiteClusters.size();
		for (int i = 0; i < 10000; i++) {
			int next = rn.nextInt(size);
			
			SiteCluster cluster = this.placeableSiteClusters.get(next);
			
			List<Site> compatible = siteTypeMap.get(cluster.getType());
			int selection = rn.nextInt(compatible.size());

			//check for an illegal move
			if (!cluster.makeMove(compatible.get(selection), this.sitenameToClusterMap, device)) {
				cluster.rejectMove();
				continue;
			}
			moves_tested++;
			//incrementally update cost of move
			int newCost = this.cost;
			
			HashSet<VirtualNet> affectedNets = cluster.getAllAffectedNets();
			for(VirtualNet net : affectedNets) {
				newCost -= this.netToCostMap[net.getUniqueID()];
				newCost += net.calculateCost();
			}
			total_cost += Math.abs(newCost - this.cost);
			cluster.rejectMove();
		}
		System.out.println("Starting Temp = " + (total_cost/moves_tested)*10);
		
		return (total_cost/moves_tested) * 10;
		
	}
	
	/**
	 * Function used to print the carry chain statistics
	 */
	@SuppressWarnings("unused")
	private void printCarryChainStatistics() {
		int count = 0, height = 0, max = 0;
		for (SiteCluster sc: this.placeableSiteClusters){
			if (sc instanceof CarryChainCluster) {
				int tmpHeight = ((CarryChainCluster)sc).getCarryChainHeight(); 
				height += tmpHeight; 
				if(tmpHeight > max)
					max = tmpHeight;
				
				count++;
			}
		}
		
		System.out.println("Carry Chains: ");
		System.out.println("\tNumber: " + count);
		System.out.println("\tAverage Height: " + (double)height / (double)count);
		System.out.println("\tTallest: " + max);
		System.out.println("\tPercentage: " + (double)count / (double)placeableSiteClusters.size() + "\n");
	}
	
	/**
	 * Updates the number of moves to do at a given temperature based on the 
	 * acceptance rate of the previous temperature.
	 */
	private int updateMovesAtTemp(double acceptanceRate){
		if(acceptanceRate > .65) {
			return 10000; 
		}
		else if(acceptanceRate > .05) { //most useful region?
			return 30000;
		}
		else {
			return 20000;
		}
	}
	
	/**
	 * Places the current design using a simulated annealing algorithm
	 */
	public void placeDesign() {
		
		// TODO: make this conditional
		if (viewCheckpoints) {
			printStatusToVivado();
		}
		
		System.out.println("TEST: " + this.sitenameToClusterMap.values().size());
		
		//uncomment if you are curious about the carry chain distribution
		//printCarryChainStatistics();
		
		double temp = this.calculateInitialTemperature(); //7000;//10000;//400;//
		
		Random rn = new Random();
		
		long start = System.currentTimeMillis();	
		int size = this.placeableSiteClusters.size();
		int next, moves, accepted, total_moves = 0;
		int movesAtTemp = 10000;
		
		double percentAccepted;
		//int test = 0;
		do {	
			moves = 0;
			accepted = 0;
		//	test++;
			
			//TODO: make the number of moves you make a function of the temperature
			while (moves < movesAtTemp) {
				moves++;
				
				//randomly choose the next site cluster to swap
				next = rn.nextInt(size);
				SiteCluster cluster = this.placeableSiteClusters.get(next);
				
				//randomly choose a new location for the site cluster 
				List<Site> compatible = siteTypeMap.get(cluster.getType());//device.getAllCompatibleSites(cluster.getType());
				int selection = rn.nextInt(compatible.size());
				
				//make a move, and check to see if it's illegal 
				if (!cluster.makeMove(compatible.get(selection), this.sitenameToClusterMap, device)) {
					cluster.rejectMove();
					continue;
				}
				
				//incrementally update cost of current solution
				int newCost = this.cost;
								
				HashSet<VirtualNet> affectedNets = cluster.getAllAffectedNets();
				for(VirtualNet net : affectedNets) {
					newCost -= this.netToCostMap[net.getUniqueID()];
					newCost += net.calculateCost();
				}
				
				//decide whether or not to keep the move
				double r = rn.nextDouble();
				int delta_cost = newCost - this.cost;
				
				if (r < Math.exp(-delta_cost/temp)) { //accept move, update data structures 
					accepted++;
				    cluster.acceptMove(this.sitenameToClusterMap);
					this.cost = newCost;
					for(VirtualNet net : affectedNets) 
						this.netToCostMap[net.getUniqueID()] = net.getCost();
				}
				else { //reject move, continue
					cluster.rejectMove();
				}
			}
		
			//calculate temperature statistics
			percentAccepted = (double)accepted / (double)moves;
			movesAtTemp = this.updateMovesAtTemp(percentAccepted);
			System.out.println("Temp: " + temp); 
			System.out.println("\tMoves: " + moves); 
			System.out.println("\tAccepted: " + accepted); 
			System.out.println("\tPercentage of moves accepted " + percentAccepted);
						
			total_moves += moves;
			temp *= .99;
			
			if (viewCheckpoints && percentAccepted < this.checkpointTimes[currentCheckpointTime]) {
				currentCheckpointTime++;
				printStatusToVivado();
			}
			
		} while (percentAccepted > .001) ; //(test < 5) ; //  
		
		//print final statistics
		System.out.println("Final Cost: " + this.cost);
		long end = System.currentTimeMillis(); 
		double duration = (double)(end - start) / 1000 ;
		System.out.println("Runtime: " + duration);
		System.out.println("Number of Moves Evaluated: " + total_moves);
		System.out.println("Moves/Second: " + (double)total_moves / duration);
		
		//test to make sure we end with the same number of sites that we started with 
		System.out.println("TEST: " + this.sitenameToClusterMap.values().size());
	
		//apply the final placement to each of the site clusters
		this.applyFinalPlacement();
	}
	
	/**
	 * Randomizes the design placement before annealing starts
	 */
	public void randomizePlacement() {
		
		HashMap<Site, SiteCluster> usedSites = new HashMap<>();
		Random rn = new Random();
		
		for(SiteCluster sc: this.placeableSiteClusters) {
			//if (sc.getType().toString().startsWith("RAMB")) {
			//	System.out.println(sc.getType());
			//}
			while (true) {
				//randomly select a site to place the cluster on
				List<Site> compatible = device.getAllCompatibleSites(sc.getType());
				int selection = rn.nextInt(compatible.size());
				
				//check to see if the placement is valid
				if(sc.placeRandomly(device, compatible.get(selection), usedSites))
					break;
			}
		}
				
		this.sitenameToClusterMap = usedSites;
		
		//check that BRAMs have initially been placed in a valid configurations 
		for (SiteCluster sc: this.placeableSiteClusters) {
			if(sc instanceof BramCluster) {
				if((sitenameToClusterMap.containsKey(sc.getCurrentTile().getSite(0))  || sitenameToClusterMap.containsKey(sc.getCurrentTile().getSite(1)))
						&& sitenameToClusterMap.containsKey(sc.getCurrentTile().getSite(2))) {
					System.out.println("BRAM 18 and 36 being occupied at the same time!!");
					throw new UnsupportedOperationException();
				}
			}
		}
	}

	/**
	 * Map all cells to bels in the design after the placement is finalized
	 */
	public void applyFinalPlacement() {
		for(SiteCluster sc : this.allSiteClusters) {
			sc.applyPlacement(design);
		}
	}
	
	private void printStatusToVivado() {
		// apply the placement temporarily
		applyFinalPlacement();
		
		try {
			System.out.println("Updating vivado with current placement...");
			XdcPlacementInterface placementInterface = new XdcPlacementInterface(design, device);
			placementInterface.writePlacementXDC(placementXdc);
			writeVivadoCommand("place_design -quiet -unplace\n");
			writeVivadoCommand("read_xdc -quiet " + placementXdc + "\n");
			writeVivadoCommand("start_gui\n");
			
			// wait for the user to continue
			MessageGenerator.agreeToContinue();
		}
		catch (IOException e) {
			throw new AssertionError("Should never reach here!");
		}
		
		// unplace all of the cells to continue placement
		unplaceAllCells();
	}
	
	private void writeVivadoCommand(String cmd) throws IOException {
		
		vivadoOut.write(cmd);
		vivadoOut.flush();
	}
}
