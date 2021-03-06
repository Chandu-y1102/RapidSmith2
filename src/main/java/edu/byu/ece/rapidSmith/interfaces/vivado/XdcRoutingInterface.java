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
package edu.byu.ece.rapidSmith.interfaces.vivado;

import edu.byu.ece.rapidSmith.design.NetType;
import edu.byu.ece.rapidSmith.design.subsite.*;
import edu.byu.ece.rapidSmith.device.*;
import edu.byu.ece.rapidSmith.device.families.FamilyInfo;
import edu.byu.ece.rapidSmith.device.families.FamilyInfos;
import edu.byu.ece.rapidSmith.interfaces.AbstractXdcInterface;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static edu.byu.ece.rapidSmith.util.Exceptions.ParseException;

/**
 * This class is used for parsing routing RSC files and writing routing XDC files in a TINCR checkpoint.
 * Routing.xdc files are used to specify the physical wires that a net in Vivado uses.
 */
public class XdcRoutingInterface extends AbstractXdcInterface {

	private final Device device;
	private final CellDesign design;
	private final CellLibrary libCells;
	private final HashMap<SitePin, IntrasiteRoute> sitePinToRouteMap;
	private final Map<BelPin, CellPin> belPinToCellPinMap;
	private final Map<SiteType, Set<String>> staticSourceMap;
	private Set<Bel> gndSourceBels;
	private Set<Bel> vccSourceBels;
	private int currentLineNumber;
	private String currentFile;
	private Map<Bel, BelRoutethrough> belRoutethroughMap;
	/** Map of partition pins (ooc ports) to their ooc tile and node **/
	private Map<String, String> partPinMap;
	private ImplementationMode implementationMode;
	private boolean pipUsedInRoute = false;
	/** INIT String for a LUT1 Buffer **/
	private static final String BUFFER_INIT_STRING = "2'h2";
	/** Nets with more than one port / partition pin as a sink **/
	private Collection<CellNet> multiPortSinkNets;

	/**
	 * Creates a new XdcRoutingInterface object.
	 * 
	 * @param design {@link CellDesign} to add routing information to
	 * @param device {@link Device} of the specified design
	 * @param pinMap A map from a {@link BelPin} to its corresponding {@link CellPin} (the cell
	 * 				pin that is currently mapped onto the bel pin)  
	 */
	public XdcRoutingInterface(CellDesign design, Device device, Map<BelPin, CellPin> pinMap, CellLibrary libCells) {
		super(device, design);
		this.device = device;
		this.design = design;
		this.sitePinToRouteMap = new HashMap<>();
		this.staticSourceMap = new HashMap<>();
		this.belPinToCellPinMap = pinMap;
		this.currentLineNumber = 0;
		this.implementationMode = design.getImplementationMode();
		this.libCells = libCells;

		if (!implementationMode.equals(ImplementationMode.REGULAR)) {
			multiPortSinkNets = getMultiPortSinkNets();
			partPinMap = new HashMap<>();
		}
	}

	public XdcRoutingInterface(CellDesign design, Device device) {
		this(design, device, null, null);
		partPinMap = design.getPartPinMap();
	}
	
	/**
	 * Returns a set of BELs that are being used as a routethrough.
	 * This is only valid after {@link XdcRoutingInterface::parseRoutingXDC} has been called.
	 * @return A map from a {@link Bel}s to {@link BelRoutethrough}s
	 */
	public Map<Bel, BelRoutethrough> getRoutethroughsBels() {
		
		return (this.belRoutethroughMap == null) ? 
				Collections.emptyMap() :
					belRoutethroughMap;
	}

	/**
	 * Returns a set of BELs that are being used as a VCC source.
	 * This is only valid after {@link XdcRoutingInterface::parseRoutingXDC} has been called.
	 */
	public Set<Bel> getVccSourceBels() {
		return (vccSourceBels == null) ?
				Collections.emptySet() :
				vccSourceBels;
	}

	/**
	 * Returns a set of BELs that are being used as a GND source.
	 * This is only valid after {@link XdcRoutingInterface::parseRoutingXDC} has been called.
	 */
	public Set<Bel> getGndSourceBels() {
		return (gndSourceBels == null) ?
				Collections.emptySet() :
				gndSourceBels;
	}


	/**
	 * Parses the specified routing.rsc file, and applies the physical wire information to the nets of the design
	 * 
	 * @param xdcFile routing.xdc file
	 * @throws IOException
	 */
	public void parseRoutingXDC(String xdcFile) throws IOException {

		currentFile = xdcFile;
		// Regex used to split lines via whitespace
		Pattern whitespacePattern = Pattern.compile("\\s+");

		// try-with-resources to guarantee no resource leakage
		try (LineNumberReader br = new LineNumberReader(new BufferedReader(new FileReader(xdcFile)))) {

			String line;
			while ((line = br.readLine()) != null) {
				this.currentLineNumber = br.getLineNumber();
				String[] toks = whitespacePattern.split(line);

				// TODO: I know the order these things appear in the file, so I probably don't need a big switch statement
				// Partition Pins must be processed before anything else (ensure they appear first in routing.rsc)
				// Update this if there is a performance issue, but it should be fine
				switch (toks[0]) {
					case "SITE_PIPS" : processSitePips(toks);
						break;
					case "INTERSITE" : processIntersitePins(toks);
						break;
					case "INTRASITE" : processIntrasiteRoute(toks);
						break;
					case "ROUTE" : processIntersiteRoutePips(toks);
						break;
					case "LUT_RTS" : processLutRoutethroughs(toks);
						break;
					case "VCC_SOURCES" : processStaticSources(toks, true);
						break;
					case "GND_SOURCES" : processStaticSources(toks, false);
						break;
					case "VCC":
					case "GND":
						String[] staticStartWires = br.readLine().split("\\s+");
						assert (staticStartWires[0].equals("START_WIRES"));
						processStaticNet2(toks, staticStartWires);
						break;
					case "PART_PIN" :
						processPartPin(toks);
						break;
					case "VCC_PART_PINS":
						processStaticPartPins(toks, true);
						break;
					case "GND_PART_PINS":
						processStaticPartPins(toks, false);
						break;
					default :
						throw new ParseException("Unrecognized Token: " + toks[0]);
				}
			}

			// compute the routing status for the GND and VCC nets at the end
			if (design.getVccNet() != null) {
				design.getVccNet().computeRouteStatus();
			}
			if (design.getGndNet() != null) {
				design.getGndNet().computeRouteStatus();
			}
		}
	}
	
	/**
	 * Loads the site PIP information for a site.
	 *  
	 * @param toks A string array of the form: <br>
	 * <br>
	 * {@code SITE_PIPS sitename pip0:input0 pip1:input1 ... pinN:inputN"} <br>
	 * <br> 
	 * where space separated elements are different elements in the array
	 */
	private void processSitePips (String[] toks) {
		Site site = tryGetSite(toks[1]);
		readUsedSitePips(site, toks);
		createStaticSubsiteRouteTrees(site);
	}
	
	/**
	 * Loads the site pin information for a net, and creates the intrasite routing
	 * connected to those site pins 
	 *  
	 * @param toks A string array of the form: <br>
	 * <br>
	 * {@code INTERSITE netName site0/pin0 site1/pin1 ... siteN/pinN"} <br>
	 * <br> 
	 * where space separated elements are different elements in the array
	 */
	private void processIntersitePins(String[] toks) {
		CellNet net = tryGetCellNet(toks[1]);
		for (int index = 2 ; index < toks.length; index++) {
			String[] sitePinToks = toks[index].split("/");
			assert (sitePinToks.length == 2);
			
			Site site = tryGetSite(sitePinToks[0]);
			SitePin pin = tryGetSitePin(site, sitePinToks[1]);

			if (pin.isInput()) { // of a site
				createIntrasiteRoute(pin, net, design.getUsedSitePipsAtSite(site));
			}
			else { // pin is an output of the site
				if (net.getSourceSitePin() != null) {
					net.addSourceSitePin(pin);
					continue;
				}
				
				// Most nets only have one source site pin, but CARRY4 cells can have "more than one" reported from vivado
				net.addSourceSitePin(pin);
				CellPin sourceCellPin = tryGetNetSource(net);
				BelPin sourceBelPin = tryGetMappedBelPin(sourceCellPin);
				createIntrasiteRoute(net, sourceBelPin, false, design.getUsedSitePipsAtSite(site));
			}
		}
		net.computeRouteStatus();
	}
	
	/**
	 * Creates routing data structures for an intrasite route
	 *  
	 * @param toks A string array of the form: <br>
	 * <br>
	 * {@code INTRASITE netName} <br>
	 * <br> 
	 * where space separated elements are different elements in the array
	 */
	private void processIntrasiteRoute(String[] toks) {
		
		CellNet net = tryGetCellNet(toks[1]);		
		
		if (net.getSourcePin() == null) {
			return;
		}
		
		CellPin sourceCellPin = tryGetNetSource(net);
		BelPin sourceBelPin = tryGetMappedBelPin(sourceCellPin);

		// There may be no source Bel Pin if the design was implemented out-of-context.
		if (sourceBelPin == null) {
			return;
		}

		Site site = sourceBelPin.getBel().getSite();
		createIntrasiteRoute(net, sourceBelPin, true, design.getUsedSitePipsAtSite(site));
		net.setIsIntrasite(true);
		net.computeRouteStatus();
	}
	
	/**
	 * Creates the routing data structures for a VCC or GND net
	 * 
	 * @param wireToks A string array of the form: <br>
	 * {@code "VCC tile0/wire0 tile1/wire1 ... tileN/wireN"} <br>
	 * where space separated elements are different elements in the array. The {@code tile/wire} elements
	 * are the wires that are used in the static net. GND could also be the first token. <br>
	 * @param startWires A string array of the form: <br>
	 * {@code "START_WIRES tile0/wire0 tile1/wire1 ... tileN/wireN"} <br>
	 * where space separated elements are different elements in the array. The {@code tile/wire} elements
	 * are the <b>starting wires</b> of the net (i.e. the wires connected to tieoffs).
	 */
	private void processStaticNet2(String[] wireToks, String[] startWires) {
		CellNet net = tryGetCellNet(wireToks[0]);
		Map<String, Set<String>> pipMap = buildPipMap(wireToks, 1);
		
		// Recreate the routing structure for each of the start wires
		// The first token is either VCC or START_WIRES, not a wire name
		for (int i = 1; i < startWires.length; i++ ) {
			Wire startWire = createTileWire(startWires[i]);
			RouteTree netRouteTree = recreateRoutingNetwork2(net, startWire, pipMap);
			net.addIntersiteRouteTree(netRouteTree);
		}
	}
	
	private Map<String, Set<String>> buildPipMap(String[] toks, int startIndex) {
		Map<String, Set<String>> pipMap = new HashMap<>();
		
		// build the pip map for connections
		for (int i = startIndex; i < toks.length; i++ ) {			
			Matcher m = pipNamePattern.matcher(toks[i]);
			
			if (m.matches()) {
				String source = m.group(1) + "/" + m.group(2);
				String sink = m.group(1) + "/" + m.group(4);
				pipMap.computeIfAbsent(source, k -> new HashSet<>()).add(sink);
				
				// if the PIP is a bi-directional pip, add both directions to the map...
				// the correct pip direction will be determined later in the routing import.
				if (m.group(3).equals("<<->>")) {
					pipMap.computeIfAbsent(sink, k -> new HashSet<>()).add(source);
				}
			}
			else {
				throw new ParseException("Invalid Pip String configuration: " + toks[i]);
			}
		}
		return pipMap;
	}
	
	/**
	 * Creates the routing data structures for a net given all the PIPs of the net.
	 * 
	 * @param toks A string array of the form: <br>
	 * {@code "ROUTE netName tile1/tileType1.wireA->wireB ... tileN/tileTypeN.wireN->wireN+1"} <br>
	 * where space separated elements are different elements in the array. The {@code tile/wire} elements
	 * are the pips used in the net {@code netName}. An example ROUTE is given below: <br>
	 * 
	 * {@code ROUTE q_reg[0]_i_1_n_0 CLBLL_L_X2Y69/CLBLL_L.CLBLL_LL_COUT->CLBLL_LL_COUT_N}
	 */
	private void processIntersiteRoutePips(String[] toks) {
		CellNet net = tryGetCellNet(toks[1]);
		Map<String, Set<String>> pipMap = buildPipMap(toks, 2);

		// There is a bug in Vivado where site pins for some nets starting at PAD's are
		// not returned through the Tcl interface.
		if (net.sourceSitePinCount() == 0 && implementationMode == ImplementationMode.REGULAR) {
			Site site = net.getSourceBelPin().getBel().getSite();
			createIntrasiteRoute(net, net.getSourceBelPin(), false, design.getUsedSitePipsAtSite(site));
		}

		assert (net.sourceSitePinCount() > 0 || implementationMode == ImplementationMode.OUT_OF_CONTEXT
				|| implementationMode == ImplementationMode.RECONFIG_MODULE)
				: net.getName() + " should have at least one source site pin";
		
		// Using the pip map, recreate each route as a RouteTree object
		List<SitePin> pinsToRemove = new ArrayList<>();
		for (SitePin sitePin : net.getSourceSitePins()) {
			RouteTree netRouteTree = recreateRoutingNetwork2(net, sitePin.getExternalWire(), pipMap);
			
			// Routes are only valid if they actually use a PIP connections. Otherwise they are unused
			if (pipUsedInRoute) {
				net.addIntersiteRouteTree(netRouteTree);
			} else {
				pinsToRemove.add(sitePin);
			}
			/*
			// If the only wire in the route is the wire connecting to the source site pin, then the
			// site pin is not a valid source, and so we remove it.
			if (netRouteTree.getChildren().size() == 0) {
				pinsToRemove.add(sitePin);
			}
			else {
				net.addIntersiteRouteTree(netRouteTree);
			}
			*/
		}
		
		// remove all invalid site pins sources for the net
		pinsToRemove.forEach(net::removeSourceSitePin);

		// For out-of-context checkpoints, look for hierarchical ports that are routed from floating wires
		if ((implementationMode == ImplementationMode.OUT_OF_CONTEXT
				|| implementationMode == ImplementationMode.RECONFIG_MODULE)
				&& net.getSourcePin().getCell().isPort()) {
			Cell port = net.getSourcePin().getCell();
			String startWireName = partPinMap.get(port.getName());
			if (startWireName != null) {
				String[] wireToks = startWireName.split("/");
				assert (wireToks.length == 2);
				Tile tile = tryGetTile(wireToks[0]);
				int wireEnum;
				if (tile.getType() == TileType.valueOf(device.getFamily(), "OOC_WIRE")) {
					wireEnum = tryGetWireEnum(wireToks[0] + "/" + wireToks[1]);
				}
				else
					wireEnum = tryGetWireEnum(wireToks[1]);

				Wire startTileWire = new TileWire(tile, wireEnum);
				RouteTree netRouteTree = recreateRoutingNetwork2(net, startTileWire, pipMap);
				net.addIntersiteRouteTree(netRouteTree);
			}
		}

		assert net.sourceSitePinCount() > 0 || implementationMode==ImplementationMode.OUT_OF_CONTEXT
				|| implementationMode==ImplementationMode.RECONFIG_MODULE
				: "Net " + net.getName() + " should have a source site pin. ";
		net.computeRouteStatus();
	}
	
	/**
	 * Creates a {@link RouteTree} data structure from a set of PIPs
	 * that are in a net. Only wire connections that are enabled are traversed. 
	 * The RouteTree that is created represents the  <b>physical intersite</b> route of the net.
	 * 
	 * @param net {@link CellNet} to create a routing data structure for
	 * @param startWire The source wire for the net (connected to a site pin). Used to initialize the route 
	 * @param pipMap A map of PIPs used in the net from source wire name -> enabled sink wire names 
	 * @return {@link RouteTree} representing the physical intersite route of the net
	 */
	private RouteTree recreateRoutingNetwork2(CellNet net, Wire startWire, Map<String, Set<String>> pipMap) {
		// initialize the routing data structure with the start wire
		this.pipUsedInRoute = false;
		RouteTree start = new RouteTree(startWire);
		Queue<RouteTree> searchQueue = new ArrayDeque<>();
		Set<Wire> visited = new HashSet<>();

		// initialize the search queue and visited wire set
		searchQueue.add(start); 
		visited.add(start.getWire());
		
		Set<String> emptySet = new HashSet<>(1);
		
		while (!searchQueue.isEmpty()) {
			
			RouteTree routeTree = searchQueue.poll();
			Wire sourceWire = routeTree.getWire();
			// add connecting wires that exist in the net to the search queue

			for (Connection conn : routeTree.getWire().getWireConnections()) {
				
				Wire sinkWire = conn.getSinkWire();
								
				if (visited.contains(sinkWire)) {
					continue;
				}
				
				if (conn.isPip()) { 
					if (pipMap.getOrDefault(sourceWire.getFullName(), emptySet).contains(sinkWire.getFullName())) {
						this.pipUsedInRoute = true;
						RouteTree sinkTree = routeTree.connect(conn);
						searchQueue.add(sinkTree);
						visited.add(sinkWire);
					}
				}
				else { // if (!visited.contains(sinkWire)) {
					RouteTree sinkTree = routeTree.connect(conn);
					searchQueue.add(sinkTree);
					visited.add(sinkWire);
				}
			}

			// check to see if the current route tree object is connected to a valid sink site pin
			SitePin sinkSitePin = routeTree.getConnectedSitePin();
			if (sinkSitePin != null)
				processSitePinSink(net, sinkSitePin);
		}

		return start;
	}
	
	private Wire createTileWire(String startWireName) {
		String[] startWireToks = startWireName.split("/");
		Tile tile = tryGetTile(startWireToks[0]);
		int wireEnum = tryGetWireEnum(startWireToks[1]);
		return new TileWire(tile, wireEnum);
	}

	/**
	 * When a route reaches a site pin, this function is called to mark sink cell pins as routed, and add pseudo pins
	 * to the design if necessary (for VCC and GND nets only).
	 *
	 * @param net {@link CellNet} the is currently being routed
	 * @param sinkSitePin {@link SitePin} that the net routing has reached
	 * @return {@code true} is connected to the net AND being used, {@code false} otherwise.
	 *
	 */
	private void processSitePinSink(CellNet net, SitePin sinkSitePin) {
		// update the net with the routed cell pins
		IntrasiteRoute internalRoute = sitePinToRouteMap.get(sinkSitePin);

		if (internalRoute != null) {
			internalRoute.setSinksAsRouted();
		}
		else if (net.isStaticNet()) {
			// implicit intrasite net. An Example is a GND/VCC net going to the A6 pin of a LUT.
			// It does not actually show the bel pin as used, but it is being used.
			// another example is the A1 pin on a SRL cell
			createStaticNetImplicitSinks(sinkSitePin, net);
		}
	}

	/**
	 * Creates a map from {@link BelPin} to {@link BelRoutethrough} for all used routethroughs
	 * found in the routing.rsc file. This map is used to successfully recreate intrasite routing
	 * for nets later in the parse process.
	 * 
	 * @param toks List of routethrough tokens in the form: <br>
	 * {@code LUT_RTS site0/bel0/inputPin0/outputPin0 site1/bel1/inputPin1/outputPin1 ...}
	 */
	private void processLutRoutethroughs(String[] toks) {
		this.belRoutethroughMap = new HashMap<>();
		
		for (int i = 1; i < toks.length; i++) {
			String[] routethroughToks = toks[i].split("/");			
			checkTokenLength(routethroughToks.length, 4);
			
			// TODO: Check that the input pin is an input pin and the output pin is an output pin?
			Site site = tryGetSite(routethroughToks[0]);
			Bel bel = tryGetBel(site, routethroughToks[1]);
			BelPin inputPin = tryGetBelPin(bel, routethroughToks[2]);
			BelPin outputPin = tryGetBelPin(bel, routethroughToks[3]);
		
			belRoutethroughMap.put(bel, new BelRoutethrough(inputPin, outputPin));
		}
	}

	/**
	 * Creates routing data structures for static nets (GND/VCC) that are sourced by static LUTs.
	 * @param toks A list of static source bels in the form: <br>
	 * {@code STATIC_SOURCES site0/bel0/outputPin0 site1/bel1/outputPin1 ... siteN/belN/outputPinN}
	 */
	private void processStaticSources(String[] toks, boolean isVcc) {

		if (isVcc && toks.length > 1) {
			this.vccSourceBels = new HashSet<>();
		}

		if (!isVcc && toks.length > 1) {
			this.gndSourceBels = new HashSet<>();
		}

		CellNet net = isVcc ? design.getVccNet() : design.getGndNet();

		for (int i = 1; i < toks.length; i++) {
			String[] staticToks = toks[i].split("/");
			checkTokenLength(staticToks.length, 3);

			Site site = tryGetSite(staticToks[0]);
			Bel bel = tryGetBel(site, staticToks[1]);
			BelPin sourcePin = tryGetBelPin(bel, staticToks[2]);
			boolean routeFound = tryCreateStaticIntrasiteRoute(net, sourcePin, design.getUsedSitePipsAtSite(site));
			assert routeFound : site.getName() + "/" + bel.getName() + "/" + sourcePin.getName();

			if (isVcc)
				vccSourceBels.add(bel);
			else
				gndSourceBels.add(bel);
		}
	}

	/**
	 *  Gets all nets that have more than one port / partition pin as a sink
	 * @return collection of nets with more than one port / partition pin as a sink
	 */
	private Collection<CellNet> getMultiPortSinkNets() {
		Collection<CellNet> nets = new ArrayList<>();
		for (CellNet net : design.getNets()) {
			if (net.getPins().stream().filter(cellPin -> cellPin.getDirection().equals(PinDirection.IN) && cellPin.getCell().isPort()).count() > 1)
				nets.add(net);
		}
		return nets;
	}

	/**
	 * Add the partition pin to the design, inserting any necessary LUT1 buffers and nets.
	 * @param portCell the ooc port to attach the partition pin to.
	 * @param partPinWire the partition pin's wire
	 * @param partPinDirection the direction of the partition pin
	 * @return The partition pin
	 */
	private CellPin addPartitionPin(Cell portCell, TileWire partPinWire, PinDirection partPinDirection) {
		assert (portCell.getPins().size() == 1);
		CellPin portCellPin = portCell.getPins().iterator().next();
		CellNet net = portCellPin.getNet();
		CellPin partPin;
		switch (partPinDirection) {
			case OUT: // If the partition pin is a driver from the RM's perspective
				// Create the partition pin
				partPin = new PartitionPin(portCell, partPinWire, partPinDirection);
				portCell.attachPartitionPin(partPin);

				// If the EDIF came from Vivado, there may be no net driving this partition pin
				// This depends on the implementation steps that have been completed.
				if (net == null) {
					net = new CellNet(partPin.getCell().getName(), NetType.WIRE);
					design.addNet(net);
				}
				else {
					// Detach the port cell pin from the net
					net.disconnectFromPin(portCellPin);
				}

				// Attach the partition pin to the net
				// Case 1: The static side is driving the partition pin, but the RM has no active loads for it.
				if (net.getFanOut() == 0) {
					// Create a LUT1 buffer. The partition pin will drive this LUT, but the LUT's output will go nowhere.
					Cell lutCell = new Cell("IN_BUF_Inserted_" + partPin.getCell().getName(), libCells.get("LUT1"));
					design.addCell(lutCell);
					lutCell.getProperties().update(new Property("INIT", PropertyType.EDIF, BUFFER_INIT_STRING));

					// Aattach the net to the partition pin and LUT1 buffer.
					net.connectToPin(partPin);
					net.connectToPin(lutCell.getPin("I0"));

				}
				else {
					// Normal case
					net.connectToPin(partPin);
				}
				break;
			case IN: // If the partition pin is a sink from the RM's perspective

				// Create the partition pin and attach it to the port.
				partPin = new PartitionPin(portCell, partPinWire, partPinDirection);
				portCell.attachPartitionPin(partPin);

				// If the EDIF came from Vivado, there may be no net driving this partition pin
				// This depends on the implementation steps that have been completed.
				if (net == null) {
					net = design.getGndNet();
				}
				else {
					// Detach the port cell pin from the net
					net.disconnectFromPin(portCellPin);
				}

				// Attach the partition pin to the net

				// Case 2: The RM is driving the partition pin with VCC or GND.
				// Case 3: The RM is driving additional partition pins with the same net.
				// Case 4: The RM is driving this partition pin with another partition pin / port
				// Whether it is considered a port or partition pin depends only on whether or not the source net was
				// processed first.
				if (net.isStaticNet() || multiPortSinkNets.contains(net) || net.getSourcePin().isPartitionPin() || net.getSourcePin().getCell().isPort()) {
					// Make a LUT1 buffer and drive it with the net.
					String bufferName = net.getName() + "_InsertedInst_" + partPin.getCell().getName();
					Cell lutCell = new Cell(bufferName, libCells.get("LUT1"));
					lutCell.getProperties().update(new Property("INIT", PropertyType.EDIF, BUFFER_INIT_STRING));
					design.addCell(lutCell);
					net.connectToPin(lutCell.getPin("I0"));

					// Make another net and connect it to the output of the LUT1 buffer and the partition pin.
					CellNet partPinDriverNet = new CellNet(net.getName() + "_InsertedNet_" + partPin.getCell().getName(), NetType.WIRE);
					design.addNet(partPinDriverNet);
					partPinDriverNet.connectToPin(lutCell.getPin("O"));
					partPinDriverNet.connectToPin(partPin);
				}
				else {
					// Normal case
					net.connectToPin(partPin);
				}
				break;
			case INOUT:
			default:
				throw new AssertionError("Invalid direction");
		}
		return partPin;
	}

	/**
	 * Processes the "PART_PIN" token in the routing.rsc of a RSCP. Specifically,
	 * this function adds the OOC port and corresponding port wire to the oocPortMap
	 * data structure for later processing.
	 *
	 * Expected Format: PART_PIN PortName Tile/Wire Direction
	 * @param toks An array of space separated string values parsed from the placement.rsc
	 */
	private void processPartPin(String[] toks) {
		assert (toks.length == 4) : String.format("Token error on line %d: Expected format is \"PART_PIN\" PortName Tile/Wire Direction", this.currentLineNumber);

		// Add the partition pin entry to the map
		partPinMap.put(toks[1], toks[2]);

		// Create and add the partition pin to the design
		String portName = toks[1];
		Cell portCell = tryGetCell(portName);

		String[] wireToks = toks[2].split("/");
		Tile tile = tryGetTile(wireToks[0]);
		String wireName;
		if (tile.getType() == TileType.valueOf(device.getFamily(), "OOC_WIRE")) {
			wireName = wireToks[0] + "/" + wireToks[1];
		} else {
			wireName = wireToks[1];
		}
		int wireEnum = tryGetWireEnum(wireName);
		TileWire partPinWire = new TileWire(tile, wireEnum);

		String direction = toks[3];
		PinDirection partPinDirection;
		switch (direction) {
			case "IN" : partPinDirection = PinDirection.IN; break;
			case "OUT" : partPinDirection = PinDirection.OUT; break;
			case "INOUT" :
			default: throw new AssertionError("Invalid direction");
		}

		CellPin partPin = addPartitionPin(portCell, partPinWire, partPinDirection);

		// Given the partition pin wire, mark all wires in the node as reserved
		design.addReservedNode(partPinWire, partPin.getNet());
	}

	/**
	 * Processes the "VCC_PART_PINS" or "GND_PART_PINS" token in the routing.rsc of an RM RSCP.
	 * Expected Format: VCC_PART_PINS partPinName partPinName ...
	 * @param toks An array of space separated string values parsed from the placement.rsc
	 */
	private void processStaticPartPins(String[] toks, boolean isVcc) {
		for (int i = 1; i < toks.length; i++) {
			String oocPortVal = isVcc? "VCC" : "GND";
			partPinMap.put(toks[i], oocPortVal);
			String portName = toks[i];
			Cell portCell = design.getCell(portName);

			// Remove the port cell's pin from the design (they are outside of the partial device)
			assert (portCell.getPins().size() == 1);
			CellPin cellPin = portCell.getPins().iterator().next();
			CellNet net = cellPin.getNet();

			CellNet staticNet = isVcc? design.getVccNet() : design.getGndNet();

			// Detach the port's pin from its current net
			// The net might be null if the design is already routed
			if (net != null) {
				net.disconnectFromPin(cellPin);

				// Re-assign the net's pins to either VCC or GND
				// Assuming driver has already been removed
				List<CellPin> pins = new ArrayList<>(net.getPins());
				net.disconnectFromPins(pins);
				design.removeNet(net);
				staticNet.connectToPins(pins);
			}

			// There is no corresponding wire for a static partition pin
			CellPin partPin = new PartitionPin(portCell, null, PinDirection.IN);
			portCell.attachPartitionPin(partPin);

			// Make the partition pin point to the appropriate global static net
			partPin.setPinToGlobalNet(staticNet);
		}
	}

	/**
	 * Parse the used PIPS within a given {@link Site}, and store that information in the current {@link CellDesign}
	 * data structure. These site pips are used to correctly import intrasite routing later in the parse process. 
	 * 
	 * @param site {@link Site} object
	 * @param toks An array of used site PIPS in the form: <br>
	 * {@code SITE_PIPS siteName pip0:input0 pip1:input1 ... pipN:inputN}
	 */
	private void readUsedSitePips(Site site, String[] toks) {
		HashSet<Integer> usedSitePips = new HashSet<>();
		String namePrefix = "intrasite:" + site.getType().name() + "/";

		//create hashmap that shows pip used to input val
		HashMap<String, String> pipToInputVal = new HashMap<>();
		
		// Iterate over the list of used site pips, and store them in the site
		for(int i = 2; i < toks.length; i++) {
			String pipWireName = (namePrefix + toks[i].replace(":", "."));
			Integer wireEnum = tryGetWireEnum(pipWireName);
			SiteWire sw = new SiteWire(site, wireEnum);
			Collection<Connection> connList = sw.getWireConnections();
			assert (connList.size() == 1 || connList.size() == 0) : "Site Pip wires should have one or no connections " + sw.getName() + " " + connList.size() ;

			if (connList.size() == 1) {
				Connection conn = connList.iterator().next();
				assert (conn.isPip()) : "Site Pip connection should be a PIP connection!";

				//add the input and output pip wires (there are two of these in RS2)
				// TODO: Is it useful to add the output wires?...I don't think these are necessary
				usedSitePips.add(wireEnum);
				usedSitePips.add(conn.getSinkWire().getWireEnum());
			}

			// If the created wire has no connections, it is a polarity selector that has been removed from the site.
			// We still need the input value in order to correctly import intra-site routing changes back into Vivado.
			String[] vals = toks[i].split(":");
			assert vals.length == 2;
			pipToInputVal.put(vals[0], vals[1]);
		}
		
		design.setUsedSitePipsAtSite(site, usedSitePips);
		design.addPIPInputValsAtSite(site, pipToInputVal);
	}
	
	/**
	 * Searches through the specified {@link Site} for VCC and GND BELs,
	 * and tries to create intrasite routing starting from those BELs if they are used.
	 * There is no way in Vivado to tell if they are used, so we have to search 
	 * each one. If the search reaches a {@link BelPin}, this means the BEL source is being
	 * used and the site is completed. 
	 *   
	 * @param site {@link Site} object
	 */
	private void createStaticSubsiteRouteTrees(Site site) {
				
		Iterator<BelPin> staticSourceIt = getPowerBelSourcesToSearch(site); 
		while (staticSourceIt.hasNext()) {
			BelPin pin = staticSourceIt.next();
			// TODO: update this with type information
			boolean isVcc = pin.getBel().getName().contains("VCC");
			CellNet net = isVcc ? design.getVccNet() : design.getGndNet();
			tryCreateStaticIntrasiteRoute(net, pin, design.getUsedSitePipsAtSite(site));
		}
	}
	
	/**
	 * Searches through all {@link Bel}s in the specified site, and returns an iterator to a
	 * list of all BELs that are VCC or GND. 
	 * 
	 * TODO: Cache the information on a {@link SiteType} basis so that the search only happens
	 * once per site type. This is especially useful for SLICEL and SLICEM types. Right now, I am
	 * caching it on a per site basis, which seems useless... 
	 * 
	 * @param site
	 * @return
	 */
	private Iterator<BelPin> getPowerBelSourcesToSearch(Site site) {
		
		Set<String> staticSourcesInSite =  staticSourceMap.get(site.getType());
		
		if (staticSourcesInSite == null) {

			staticSourcesInSite = site.getBels().stream()
									.filter(bel -> bel.getName().contains("VCC") || bel.getName().contains("GND"))
									.map(Bel::getName)
									.collect(Collectors.toSet());

			staticSourceMap.put(site.getType(), staticSourcesInSite);
		}

		return staticSourcesInSite.stream()
							.map(site::getBel)
							.flatMap(bel -> bel.getSources().stream())
							.iterator();
	}

	/**
	 * Creates intrasite routing data structures for the route starting at the specified
	 * {@link SitePin}. In particular, this is for static nets (GND/VCC) that have
	 * terminated at a site pin, but the site pin has not been registered earlier during parsing.
	 * This is due to the net connecting to a BelPin, but not a CellPin. In this case, a <b>pseudo pin</b>
	 * will be created and attached to a cell if one exists on the bel.
	 *
	 * @param sitePin {@link SitePin} object
	 * @param net VCC or GND {@link CellNet}
	 */
	private void createStaticNetImplicitSinks(SitePin sitePin, CellNet net) {

		IntrasiteRoute staticRoute = new IntrasiteRouteSitePinSource(sitePin, net, true);
		buildIntrasiteRoute(staticRoute, design.getUsedSitePipsAtSite(sitePin.getSite()));

		if (!staticRoute.isValid()) {
			// NOTE: there are cases in ultrascale where a static net connects to a site pin, but the signal goes nowhere in the site
			// In this case, we simply return and don't apply the routing.
			return;
		}
		staticRoute.applyRouting();
		// we can set sinks as routed here because we only call this function if we reach a site pin
		staticRoute.setSinksAsRouted();
	}

	/**
	 * Creates a route starting at the specified {@link SitePin}, which terminates at BelPins inside the site.
	 * The search is guided by the specified used site pips of the site.
	 * If no valid route is found, an exception is thrown because this function.
	 * expects a route to be found 
	 * 
	 * @param pin Site Pin to start the route
	 * @param net Net attached to that site pin
	 * @param usedSiteWires Set of used pips within the site
	 */
	private void createIntrasiteRoute(SitePin pin, CellNet net, Set<Integer> usedSiteWires) {
	
		IntrasiteRoute route = new IntrasiteRouteSitePinSource(pin, net, false);
		buildIntrasiteRoute(route, usedSiteWires);
		
		if (!route.isValid()) {			
			throw new AssertionError("Valid intrasite route not found from site pin : " + pin + " Net: " + net.getName());
		}
		
		route.applyRouting();
		sitePinToRouteMap.put(pin, route);
	}
	
	/**
	 * Creates a route starting at the specified {@link BelPin}, and terminates in either BelPins or {@link SitePin}s.
	 * The search is guided by the used site pips of the site. If no valid route is found, an exception is thrown 
	 * 
	 * @param pin Bel Pin to start the route
	 * @param isContained True if all sinks of the net are in the same site as the source
	 * @param usedSiteWires Set of used pips within the site
	 */
	private void createIntrasiteRoute(CellNet net, BelPin pin, boolean isContained, Set<Integer> usedSiteWires) {
	
		IntrasiteRoute route = new IntrasiteRouteBelPinSource(net, pin, isContained);
		buildIntrasiteRoute(route, usedSiteWires);
		
		if (!route.isValid()) {
			throw new AssertionError("Valid intrasite route not found from bel pin : " + pin);
		}
		
		route.applyRouting();
		route.setSinksAsRouted();
	}
	
	/**
	 * Tries to create a route starting at a GND or VCC bel. <br>
	 * Because no route is guaranteed to exist, routing is only applied <br>
	 * if a valid route is found. Otherwise, nothing happens.
	 * 
	 * @param pin BelPin to start the search
	 * @param usedSiteWires Used site pips in the site
	 * @return True if a route is found
	 */
	private boolean tryCreateStaticIntrasiteRoute(CellNet net, BelPin pin, Set<Integer> usedSiteWires) {
		
		IntrasiteRoute route = new IntrasiteRouteBelPinSource(net, pin, false);
		buildIntrasiteRoute(route, usedSiteWires);
		
		if (route.isValid()) {
			route.applyRouting();
			route.setSinksAsRouted();
			return true;
		}
		return false;
	}
	
	/**
	 * Performs an intrasite search starting at either a {@link BelPin} or {@link SitePin}, 
	 * creates a RouteTree data structure of the search, and records all BelPin and SitePin sinks 
	 * of the search. See the {@link IntrasiteRoute} interface to see methods that are called from this function  
	 * 
	 * @param intrasiteRoute {@link IntrasiteRoute} interface. See {@link IntrasiteRouteSitePinSource} and
	 * 						{@link IntrasiteRouteBelPinSource} for more details
	 * @param usedSiteWires a set to insert the used site wires into
	 */
	private void buildIntrasiteRoute(IntrasiteRoute intrasiteRoute, Set<Integer> usedSiteWires) {
		
		// Initialize the search
		Set<Wire> visitedWires = new HashSet<>(); // used to prevent cycles
		Queue<RouteTree> routeQueue = new LinkedList<>();
		
		RouteTree startRoute = intrasiteRoute.getStartRoute();
		Wire startWire = startRoute.getWire();
		routeQueue.add(startRoute);
		visitedWires.add(startWire);
		
		// continue the search until we have nowhere else to go
		while (!routeQueue.isEmpty()) {
			RouteTree currentRoute = routeQueue.poll();
			Wire currentWire = currentRoute.getWire();

			// reached a used bel pin that is not the source
			if (intrasiteRoute.isValidBelPinSink(currentWire) && !currentWire.equals(startWire)) {
				BelPin bp = currentWire.getTerminal();
				intrasiteRoute.addBelPinSink(bp, currentRoute);
			}
			// reached a site pin
			else if (connectsToSitePin(currentWire)) {
				SitePin sinkPin = currentWire.getConnectedPin();
				intrasiteRoute.addSitePinSink(sinkPin, currentRoute);
			}
			else {
				
				for (Connection conn : currentWire.getWireConnections()) {
										
					// skip wires we already visited
					if (visitedWires.contains(conn.getSinkWire())) {
						continue;
					}
					
					// only add valid search connections to the queue
					if (isQualifiedConnection(conn, currentWire, usedSiteWires)) {
						RouteTree next = currentRoute.connect(conn);
						routeQueue.add(next);
						visitedWires.add(next.getWire());
					}
				}
			}
		}
		
		// prune the route tree 
		intrasiteRoute.pruneRoute();
	}
	
	/**
	 *	Returns <code>true</code> if the specified wire connects to 
	 *a {@link SitePin}, <code>false</code> otherwise.
	 * 
	 * @param currentWire {@link Wire} object
	 */
	private boolean connectsToSitePin(Wire currentWire) {
		return currentWire.getConnectedPin() != null;
	}
	
	/**
	 * Returns true if the specified {@link Connection} object is a valid connection to follow in the site. 
	 * A connection is valid if one of the following are satisfied: <br>
	 * <p> <ul>
	 * <li> The connection is a <b>NON-PIP</b> wire connection  
	 * <li> The connection is a used LUT routethrough
	 * <li> The connection is a PIP wire connection that is used
	 * </ul><p> 
	 * 
	 * @param conn {@link Connection} object
	 * @param sourceWire The source {@link Wire} of the connection
	 * @param usedSiteWires A set of used wires in the {@link Site} that is currently being searched
	 */
	private boolean isQualifiedConnection(Connection conn, Wire sourceWire, Set<Integer> usedSiteWires) {
				
		return !conn.isPip() || // the connection is a regular wire connection
				isUsedRoutethrough(conn, sourceWire) || // or, the connection is a used lut routethrough 
				usedSiteWires.contains(sourceWire.getWireEnum()); // or the connection is a used site pip
	}
	
	/**
	 * Returns true if the given connections is a used BEL routethrough. <br>
	 * A connection satisfies this condition if: <br>
	 * <p> <ul>
	 * <li>(1) The connection is a routethrough <br>
	 * <li>(2) The BelPin is a key in the usedRoutethroughMap <br>
	 * <li>(3) The sink wire of the connection matched the value in the usedRoutethroughMap <br>
	 * </p> </ul>
	 * 
	 * @param conn Connection to test 
	 * @return True if the Connection is an available routethrough. False otherwise.
	 */
	private boolean isUsedRoutethrough(Connection conn, Wire sourceWire) {
		
		if (!conn.isRouteThrough()) {
			return false;
		}
		
		// a bel routethrough must also be connected to a BEL pin 
		assert sourceWire.getTerminal() != null : "Wire: " + sourceWire + " should connect to BelPin!";
		BelPin source = sourceWire.getTerminal();
		
		BelRoutethrough routethrough = this.belRoutethroughMap.get(source.getBel());
		
		return routethrough != null && routethrough.getOutputWire().equals(conn.getSinkWire());
	}
	
	/**
	 * Returns {@code true} if the specified {@link BelPin} is being used 
	 * in the design (i.e. a {@link CellPin} is mapped to it), {@code false} otherwise
	 */
	private boolean isBelPinUsed(BelPin pin) {
		return belPinToCellPinMap.containsKey(pin);
	}
	
	/**
	 * Compares the actual token length of a line in the routing.rsc file against the 
	 * expected token length. If they do not agree, a {@link ParseException} is thrown.
	 */
	private void checkTokenLength(int tokenLength, int expectedLength) {
		
		if (tokenLength != expectedLength) {
			throw new ParseException(String.format("Incorrect number of tokens on line %d of %s.\n"
												+ "Expected: %d Actual: %d", currentLineNumber, currentFile, tokenLength, expectedLength));
		}
	}

	/**
	 * Tries to retrieve the CellNet object with the given name <br>
	 * from the currently loaded design. If the net does not exist <br>
	 * a ParseException is thrown.
	 * 
	 * @param netName Name of net to retrieve
	 * @return CellNet
	 */
	private CellNet tryGetCellNet(String netName) {
		CellNet net;
		switch (netName) {
			case "VCC":
				net = design.getVccNet();
				break;
			case "GND":
				net = design.getGndNet();
				break;
			default:
				net = design.getNet(netName);
				break;
		}
		
		if (net == null) {
			throw new ParseException("Net \"" + netName + "\" does not exist in the current design. Validate Edif is correct.\n" 
									+ "On line " + this.currentLineNumber + " of " + currentFile);
		}
				
		return net;
	}
	
	/**
	 * Tries to retrieve the SitePin object specified by the site <br>
	 * and pin name. If the pin does not exist, a ParseException is thrown. <br>
	 *  
	 * @param site Site object
	 * @param pinName Name of the pin on the site
	 * @return SitePin
	 */
	private SitePin tryGetSitePin(Site site, String pinName) {
		SitePin pin = site.getPin(pinName);
		
		if (pin == null) {
			throw new ParseException(String.format("SitePin: \"%s/%s\" does not exist in the current device\n"
												   + "On line %d of %s", site.getName(), pinName, currentLineNumber, currentFile));
		}
		return pin;
	}
	
	/**
	 * Tries to retrieve the source of a CellNet. If the net does not have a source, <br>
	 * a ParseException is thrown because all nets with routing information are expected <br>
	 * to have a source cell pin.
	 * 
	 * @param net CellNet to get the source of
	 * @return CellPin
	 */
	private CellPin tryGetNetSource(CellNet net) {
		CellPin source = net.getSourcePin();
		
		if (source == null) {
			throw new ParseException("Net \"" + net.getName() +"\" does not have a source pin!\n" 
									+ "On line " + this.currentLineNumber + " of " + currentFile);
		}
		return source;
	}
	
	/**
	 * Tries to get the BelPin that the specified CellPin is mapped to.
	 * If this function is called, it is expected that the CellPin maps
	 * to exactly one BelPin (it is a source pin). 
	 * @param cellPin CellPin to get the BelPin mapping of
	 * @return BelPin
	 */
	private BelPin tryGetMappedBelPin(CellPin cellPin) {
		int mapCount = cellPin.getMappedBelPinCount();

		// Some out of context designs will not have cells, so there will be no mapped BelPin.
		if (mapCount != 1 && (implementationMode == ImplementationMode.OUT_OF_CONTEXT
				|| implementationMode==ImplementationMode.RECONFIG_MODULE)) {
			return null;
		}
		else if (mapCount != 1) {
			throw new ParseException(String.format("Cell pin source \"%s\" should map to exactly one BelPin, but maps to %d\n"
					+ "On %d of %s", cellPin.getName(), mapCount, currentLineNumber, currentFile));
		}
		return cellPin.getMappedBelPin();
	}

	/**
	 * Writes the intrasite routing TCL commands for the design to the routing.xdc file.
	 * For now, only support slices. Other site types don't have much flexibility in routing.
	 * Additionally, trying to set the intrasite routing of IOB sites (IOB33, IOB33S, IOB33M) causes Vivado to crash.
	 *
	 * @param design Design with nets to export
	 * @param fileout BufferedWriter for the routing.xdc file
	 * @throws IOException if the file can't be written to
	 */
	private void writeIntrasiteRouting(CellDesign design, BufferedWriter fileout) throws IOException {
		FamilyInfo familyInfo = FamilyInfos.get(device.getFamily());

		for (Site site : design.getUsedSites()) {
			if (!familyInfo.sliceSites().contains(site.getType()))
				continue;

			// Get all site PIP values for the site (excluding polarity selectors)
			Map<String, String> pipInfo = design.getPIPInputValsAtSite(site);

			if (pipInfo == null || pipInfo.isEmpty())
				continue;

			fileout.write(String.format("set_property MANUAL_ROUTING %s [get_sites {%s}]\n", site.getType().name(), site.getName()));

			// Build up the SITE_PIPS property for the site
			StringBuilder sitePips = new StringBuilder();
			for (Map.Entry<String, String> entry : pipInfo.entrySet()) {
				sitePips.append(entry.getKey()).append(":").append(entry.getValue()).append(" ");
			}

			// Handle polarity selectors.
			if (FamilyInfos.SERIES7_FAMILIES.contains(device.getFamily())) {
				// For series 7 devices, we must obtain the value of the CLKINV polarity selector
				Cell ffLatchCell = design.getCellsAtSite(site).stream()
						.filter(cell -> cell.isFlipFlop() || cell.isLatch())
						.findAny().orElse(null);

				if (ffLatchCell != null) {
					Property clkInvProperty = ffLatchCell.getProperties().get("IS_C_INVERTED");
					String clkInvValue = (clkInvProperty == null) ? "1'b0" : clkInvProperty.getStringValue();

					// For series 7, polarity selectors have two inputs and so the appropriate value must be
					// included in the SITE_PIPS property.
					if (clkInvValue.equals("1'b1"))
						sitePips.append("CLKINV:CLK_B");
					else
						sitePips.append("CLKINV:CLK");
				}
			}
			else if (FamilyInfos.ULTRASCALE_FAMILIES.contains(device.getFamily())) {
				// In Ultrascale, polarity selectors only have a single input and are always configured the same in
				// the SITE_PIPS property. Still, if a polarity selector is used, it must be included in the SITE_PIPS property.
				// For simplicity, we set all polarity selectors for Ultrascale to their only possible values.
				if (site.getType().equals(SiteType.valueOf(device.getFamily(), "SLICEM")))
					sitePips.append("LCLKINV:CLK ");
				sitePips.append("CLK1INV:CLK ");
				sitePips.append("CLK2INV:CLK ");
				sitePips.append("RST_ABCDINV:RST ");
				sitePips.append("RST_EFGHINV:RST");
			}

			// Write used site PIPs (intrasite routing information)
			fileout.write(String.format("set_property SITE_PIPS {%s} [get_sites {%s}]\n", sitePips.toString(), site.getName()));
		}
	}

	/**
	 * Gets the true children (the programmable children) of a route tree
	 * @param children the original children
	 * @return the true children
	 */
	private static ArrayList<RouteTree> getTrueChildren(ArrayList<RouteTree> children) {
		ArrayList<RouteTree> trueChildren = new ArrayList<>();
		for (int i = 0; i < children.size(); i++) {
			RouteTree child = children.get(i);
			Connection c = child.getConnection();
			if (c.isPip() || c.isRouteThrough()) {
				trueChildren.add(child);
			}
			else { // if its a regular wire connection and we don't want to add this to the route tree
				children.addAll(child.getChildren());
			}
		}

		return trueChildren;
	}

	/**
	 * Creates a route string tree from a route tree.
	 * @param rt the route tree
	 * @return the route string tree
	 */
	private static RouteStringTree createRouteStringTree (RouteTree rt) {
		RouteTree currentRoute = rt;
		RouteStringTree root = null;
		RouteStringTree stringTree = null;

		while ( true ) {
			String rstName;
			if (currentRoute.getWire().getTile().getName().equals("OOC_WIRE_X0Y0"))
				rstName = currentRoute.getWire().getName();
			else
				rstName = currentRoute.getWire().getFullName();

			if (stringTree == null) {
				stringTree = new RouteStringTree(rstName);
				root = stringTree;
			}
			else {
				stringTree = stringTree.addChild(new RouteStringTree(rstName));
			}

			// children may be changed in the following method call, so make a copy
			ArrayList<RouteTree> children = new ArrayList<>(currentRoute.getChildren());
			if (children.size() == 0)
				break;

			ArrayList<RouteTree> trueChildren = getTrueChildren(children);
			if (trueChildren.size() == 0)
				break;

			for(int i = 0; i < trueChildren.size() - 1; i++) {
				stringTree.addChild(createRouteStringTree(trueChildren.get(i)));
			}

			currentRoute = trueChildren.get(trueChildren.size() - 1) ;

		}

		return root;
	}

	/**
	 *
	 * @param moduleRouteTree
	 * @param staticRouteStringTree
	 * @param partPinNode
	 * @param rmSource whether or not the part pin acts as a source to this design (the reconfigurable module)
	 */
	private RouteStringTree mergePartialStaticRoute(RouteTree moduleRouteTree, RouteStringTree staticRouteStringTree, String partPinNode, boolean rmSource) {
		// Convert the reconfigurable module's route tree to a route string tree
		RouteStringTree moduleRouteStringTree = createRouteStringTree(moduleRouteTree.getRoot());
		RouteStringTree toMerge = rmSource ? staticRouteStringTree : moduleRouteStringTree;
		RouteStringTree toAdd = rmSource ? moduleRouteStringTree : staticRouteStringTree;

		// Merge the trees at the partition pin
		assert (toAdd.getWireName().equals(partPinNode));
		toMerge.addChildrenFromTree(toAdd);

		return toMerge;
	}

	/**
	 * Creates a routing.xdc file from the nets of the given design. <br>
	 * This file can be imported into Vivado to constrain the physical location of nets. 
	 * 
	 * @param xdcOut Location to write the routing.xdc file
	 * @param oocXdcOut Location to write the part_pin_routing.xdc file
	 * @param design Design with nets to export
	 * @param intrasiteRouting Whether to export commands to manually set the intrasite routing in Vivado
	 * @throws IOException if the file {@code xdcOut} could not be opened
	 */
	public void writeRoutingXDC(String xdcOut, String oocXdcOut, CellDesign design, boolean intrasiteRouting) throws IOException {
		ArrayList<CellNet> sourceNets = new ArrayList<>();
		ArrayList<CellNet> sinkNets = new ArrayList<>();
		BufferedWriter fileout = new BufferedWriter (new FileWriter(xdcOut));

		if (intrasiteRouting) {
			// Write the intra-site routing commands for the design
			writeIntrasiteRouting(design, fileout);
		}

		// Write the inter-site routing information for each net
		for(CellNet net : design.getNets()) {

			// only print nets that have routing information. Grab the first RouteTree of the net and use this as the final route
			if (net.getIntersiteRouteTree() != null ) {

				// If RM, build lists of source nets and sink nets.
				// These routes are exported to the oocRouting XDC file.
				if (implementationMode == ImplementationMode.RECONFIG_MODULE) {
					if (net.getSourcePin().isPartitionPin()) {
						sourceNets.add(net);
						continue;
					}
					else if (!net.isGNDNet() && net.getSinkPins().size() > 0){
						// If any of the sinks are partition pins, add the net to the list of sinkNets
						if (net.getSinkPins().stream().anyMatch(CellPin::isPartitionPin)) {
							sinkNets.add(net);
							continue;
						}
					}
				}
				fileout.write(String.format("set_property ROUTE %s [get_nets {%s}]\n", getVivadoRouteString(net), net.getName()));
			}
		}

		fileout.close();

		// Now write the OOC RoutingXDC
		if (oocXdcOut != null) {
			BufferedWriter oocFileOut = new BufferedWriter (new FileWriter(oocXdcOut));

			for (CellNet net : sourceNets) {
				String portName = net.getSourcePin().getCell().getName();

				// Find the matching static net from the static-only design
				String staticNetName = design.getRmStaticNetMap().get(net.getName());

				// Merge the static and RM portions of the route
				String partPinNode = partPinMap.get(portName);
				assert (partPinNode != null);
				RouteStringTree mergedTree = mergePartialStaticRoute(net.getIntersiteRouteTree(), design.getStaticRouteStringMap().get(staticNetName), partPinNode, true);

				oocFileOut.write(String.format("set_property ROUTE %s [get_nets {%s}]\n", mergedTree.toRouteString(), design.getRmStaticNetMap().get(net.getName())));
			}

			for (CellNet net : sinkNets) {
				for (CellPin partPin : net.getPartitionPins()) {
					String portName = partPin.getCell().getName();

					// Find the matching static net from the static-only design
					String staticNetName = design.getRmStaticNetMap().get(net.getName());

					// Merge the static and RM portions of the route
					String partPinNode = partPinMap.get(portName);

					assert(partPinNode != null);
					RouteStringTree mergedTree = mergePartialStaticRoute(net.getIntersiteRouteTree(), design.getStaticRouteStringMap().get(staticNetName), partPinNode, false);
					oocFileOut.write(String.format("set_property ROUTE %s [get_nets {%s}]\n", mergedTree.toRouteString(), design.getRmStaticNetMap().get(net.getName())));
				}
			}
			oocFileOut.close();
		}

	}

	public void writeRoutingXDC(String xdcOut, CellDesign design, boolean intrasiteRouting) throws IOException {
		writeRoutingXDC(xdcOut, null, design, intrasiteRouting);
	}

	/**
	 * Creates the Vivado equivalent route string of the specified net.
	 * If the net is a generic net (i.e. not VCC or GND), the first RouteTree
	 * in the net's list of RouteTrees is assumed to be the route to print.
	 * For GND and VCC nets, all RouteTrees in the net's list of RouteTrees
	 * are printed. This function can be used to incrementally update the
	 * ROUTE property of a net in Vivado.
	 *
	 * @param net CellNet to create a Vivado ROUTE string for
	 * @return Vivado ROUTE string
	 */
	public static String getVivadoRouteString(CellNet net) {
		if (net.getIntersiteRouteTreeList().size() == 1) {
			RouteTree route = net.getIntersiteRouteTree();
			return createVivadoRoutingString(route.getRoot());
		}
		
		// otherwise we assume its a VCC or GND net, which has a special Route string
		StringBuilder routeString = new StringBuilder("\" ");
		for (RouteTree rt : net.getIntersiteRouteTreeList()) {
			routeString.append("( ").append(createVivadoRoutingString(rt.getRoot())).append(") ");
		}

		return routeString + "\"";
	}
	
	/*
	 * Creates and formats the route tree into a string that Vivado understands and can be applied to a Vivado net
	 * TODO: refactor...this code is confusing to read
	 */
	private static String createVivadoRoutingString (RouteTree rt) {
		
		RouteTree currentRoute = rt; 
		String routeString = "{ ";
			
		while ( true ) {
			Tile t = currentRoute.getWire().getTile();
			routeString = routeString.concat(t.getName() + "/" + currentRoute.getWire().getName() + " ");
						
			// children may be changed in the following method call, so make a copy
			ArrayList<RouteTree> children = new ArrayList<>(currentRoute.getChildren());
			
			if (children.size() == 0)
				break;

			ArrayList<RouteTree> trueChildren = getTrueChildren(children);
			
			if (trueChildren.size() == 0)
				break;
			
			for(int i = 0; i < trueChildren.size() - 1; i++) 
				routeString = routeString.concat(createVivadoRoutingString(trueChildren.get(i)));
			
			currentRoute = trueChildren.get(trueChildren.size() - 1) ; 
		}
		
		return routeString + "} ";
	}

	/**
	 * @return the partPinMap
	 */
	public Map<String, String> getPartPinMap() {
		return partPinMap;
	}

	/* **************
	 * 	Nested Types
	 * **************/
	
	/**
	 * Interface used to create an intrasite route sourced from either a {@link BelPin} or {@link SitePin}
	 * 
	 * @author Thomas Townsend
	 */
	public interface IntrasiteRoute {
		boolean addBelPinSink(BelPin belPin, RouteTree terminal);
		boolean addSitePinSink(SitePin sitePin, RouteTree terminal);
		void pruneRoute();
		void applyRouting();
		boolean isValid();
		RouteTree getStartRoute();
		void setSinksAsRouted();
		boolean isValidBelPinSink(Wire currentWire);
	}
	
	/**
	 * Class used to represent an IntrasiteRoute starting at a {@link SitePin}
	 */
	public final class IntrasiteRouteSitePinSource implements IntrasiteRoute {
		
		private final SitePin source;
		private final CellNet net; 
		private final Set<BelPin> belPinSinks;
		private final Set<RouteTree> terminals;
		private final RouteTree route;
		private final boolean allowUnusedBelPins; 
		
		public IntrasiteRouteSitePinSource (SitePin source, CellNet net, boolean allowUnusedBelPinSinks) {
			this.source = source;
			this.net = net;
			this.belPinSinks = new HashSet<>();
			this.terminals = new HashSet<>();
			this.route = new RouteTree(source.getInternalWire());
			this.allowUnusedBelPins = allowUnusedBelPinSinks;
		}
				
		@Override
		public RouteTree getStartRoute() {
			return route;
		}
	
		@Override
		public boolean addBelPinSink(BelPin belPin, RouteTree terminal) {
			
			CellPin sinkCellPin = belPinToCellPinMap.get(belPin);
			
			if (allowUnusedBelPins) {
				if (sinkCellPin != null) {
					
					this.net.connectToPin(sinkCellPin);
					// TODO: this was an old assumption that does not hold true for ultrascale ... look at this more 
					//throw new AssertionError("Only unused bel pin sinks expected: " + net.getName() + " " + belPin + " " + sinkCellPin.getFullName());
				}
			}
			else {
				if (sinkCellPin == null || !Objects.equals(sinkCellPin.getNet(), this.net)) {
					return false;
				}
			}
						
			terminals.add(terminal);
			belPinSinks.add(belPin);
			return true;
		}

		@Override
		public boolean addSitePinSink(SitePin sitePin, RouteTree terminal) {
			return false; 
			// TODO: this was an old assumption that does not hold true for ultrascale 
			// now we return false (we don't add it as a sink)
			//throw new AssertionError("Intrasite Route starting at input site pin should not reach output site pin " + source);
		}

		@Override
		public void pruneRoute() {
			route.prune(terminals);
		}

		@Override
		public void applyRouting() {
			net.addSinkRouteTree(source, route);

			for (BelPin pin : this.belPinSinks) {
				net.addSinkRouteTree(pin, route);

				if (allowUnusedBelPins && !belPinToCellPinMap.containsKey(pin)) {
					createAndAttachPseudoPin(pin);
				}
			}
		}

		@Override
		public boolean isValid() {
			return belPinSinks.size() > 0;
		}
		
		@Override
		public void setSinksAsRouted() {
			// TODO: only do this for used bel pins
			belPinSinks.stream()
						.filter(belPinToCellPinMap::containsKey)
						.map(belPinToCellPinMap::get)
						.forEach(net::addRoutedSink);
		}

		@Override
		public boolean isValidBelPinSink(Wire currentWire) {
			
			BelPin terminal = currentWire.getTerminal();
						
			// BEL pin sink is valid if the wire connects to
			// a bel pin and either:
			// (1) The net is a static net (GND or VCC)
			// (2) The BelPin is being used (i.e. a cell pin has been mapped to it)
			return terminal != null && (allowUnusedBelPins || isBelPinUsed(terminal));
		}
		
		// TODO: document this
		private void createAndAttachPseudoPin(BelPin belPin) {
			Cell cell = design.getCellAtBel(belPin.getBel());
			
			// Leads to a routethrough bel. Currently we cannot attach a pseudo pin to a bel, so we return.
			// TODO: create routethroughs as we import so we will never run into this case?
			if (cell == null) {
				// System.out.println("RouteThrough Test: " + belPin.getBel().getFullName() + "/" + belPin.getName());
				return;
			}
			
			assert (cell != null) : "Expected a cell to be mapped to a bel." ;
			assert (net.isStaticNet()) : "Net should be a static net!";
			String pinName = (net.isVCCNet() ? "VCC_pseudo" : "GND_pseudo") + cell.getPseudoPinCount();
			CellPin pseudo = cell.attachPseudoPin(pinName, belPin.getDirection());
			assert (pseudo != null) : "Pseudo pin should never be null!";
			net.connectToPin(pseudo);
			pseudo.mapToBelPin(belPin);
			belPinToCellPinMap.put(belPin, pseudo);
		}
	}
	
	/**
	 * Class used to represent an IntrasiteRoute starting at a {@link BelPin}
	 */
	public final class IntrasiteRouteBelPinSource implements IntrasiteRoute {
		
		private final BelPin source;
		private final Set<BelPin> belPinSinks;
		private final Set<SitePin> sitePinSinks;
		private final RouteTree route;
		private final Set<RouteTree> terminals;
		private final boolean isContained;
		private CellNet net; 
		
		public IntrasiteRouteBelPinSource (CellNet net, BelPin source, boolean isContained) {
			this.net = net;
			this.source = source;
			this.isContained = isContained;
			this.belPinSinks = new HashSet<>();
			this.sitePinSinks = new HashSet<>();
			this.terminals = new HashSet<>();
			this.route = new RouteTree(source.getWire());
		}

		@Override
		public boolean addBelPinSink(BelPin belPin, RouteTree terminal) {

			this.belPinSinks.add(belPin);
			this.terminals.add(terminal);
			
			return true;
		}

		@Override
		public boolean addSitePinSink(SitePin sitePin, RouteTree terminal) {
			
			if (isContained) {
				return false;
			}
						
			sitePinSinks.add(sitePin);
			terminals.add(terminal);
			return true;
		}

		@Override
		public void pruneRoute() {
			route.prune(terminals);
		}

		@Override
		public void applyRouting() {
					
			// statically sourced nets (VCC and GND) don't have a net attached to the bel pin
			if (!net.isStaticNet()) {
				
				CellNet net1 = belPinToCellPinMap.get(source).getNet();
				assert(net1 == net) : "Nets should be identical";
				net.setSourceRouteTree(route);
				
				boolean noSitePinSources = (net.sourceSitePinCount() == 0);
				
				for (SitePin sp : sitePinSinks) {
					// this part of the code is for fixing nets for differential input
					if (noSitePinSources && sp.isOutput()) {
						net.addSourceSitePin(sp);
					}
					
					net.addSinkRouteTree(sp, route);
				}
			}
			
			for (BelPin bp: belPinSinks) {
				CellPin pin = belPinToCellPinMap.get(bp);

				 // Fix Vivado EDIF errors where cell pins aren't included in the netlist, but are physically routed to
				if (pin.getNet() == null) {
					net.connectToPin(pin);
				} 
				else if (pin.getNet() != net) {
					assert pin.getNet().isStaticNet() : "Net mismatch should only happen for static nets";
					assert belPinSinks.size() == 1 : "Only one bel pin expected";
					net = pin.getNet();
				}
				
				net.addSinkRouteTree(bp, route);
			}
		}

		@Override
		public boolean isValid() {
			
			// completely intrasite routes should not hit a site pin
			// || isStatic
			if (isContained) {
				return belPinSinks.size() > 0 && sitePinSinks.size() == 0;
			}
			
			return belPinSinks.size() > 0 || sitePinSinks.size() > 0;
		}

		@Override
		public RouteTree getStartRoute() {
			return route;
		}
		
		@Override
		public void setSinksAsRouted() {
			// by definition, if the source of a intra-site net is a bel pin,
			// then all bel pin sinks are routed
			for (BelPin belPin : belPinSinks) {
				CellPin cellPin = belPinToCellPinMap.get(belPin);
				net.addRoutedSink(cellPin);
			}
		}

		@Override
		public boolean isValidBelPinSink(Wire currentWire) {
			BelPin terminal = currentWire.getTerminal();
			return terminal != null && isBelPinUsed(terminal);
		}
	}
}
