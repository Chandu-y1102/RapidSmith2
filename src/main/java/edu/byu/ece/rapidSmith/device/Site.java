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
package edu.byu.ece.rapidSmith.device;

import java.io.Serializable;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This class represents the sites found in a Xilinx device.  Sites are a collection
 * of closely interconnected BELs.
 * @author Chris Lavin
 */
public final class Site implements Serializable{
	private static final long serialVersionUID = -3823244747162695622L;
	/** Name of the site with X and Y coordinates (ie. SLICE_X0Y0) */
	private String name;
	/** The index in the tile's list of Sites */
	private int index;
	/** The tile where this site resides */
	private Tile tile;
	/** The X coordinate of the instance (ex: SLICE_X#Y5) */
	private Integer instanceX;
	/** The Y coordinate of the instance (ex: SLICE_X5Y#) */
	private Integer instanceY;
	/** The bondedness of the site */
	private BondedType bondedType;
	/** Stores the template of the type that has been assigned to this site. */
	private SiteTemplate template;
	/** List of possible types for this site. */
	private SiteType[] possibleTypes;
	/**
	 * A map of the external wire each pin connects to for each site type this
	 * site can be represented as.
	 */
	private Map<SiteType, Map<String, Integer>> externalWires;
	/**
	 * Map of the site pin each wire connecting to the site connects to for each
	 * site type this site can be represented as.
	 */
	private Map<SiteType, Map<Integer, SitePinTemplate>> externalWireToPinMap;

	/**
	 * Constructor unnamed, tileless site.
	 */
	public Site(){
		name = null;
		tile = null;
		instanceX = -1;
		instanceY = -1;
	}
	
	/**
	 * Returns the name of this site (ex: SLICE_X4Y6).
	 * @return the unique name of this site.
	 */
	public String getName(){
		return name;
	}
	
	/**
	 * Sets the name of this site (ex: SLICE_X5Y7).
	 * @param name the name to set.
	 */
	public void setName(String name){
		this.name = name;
	}

	/**
	 * Returns the index of this site in it tile's Site list.
	 * @return this site's index
	 */
	public int getIndex() {
		return index;
	}

	/**
	 * Sets the index of this site in its tile's Site list.
	 * @param index index of this site
	 */
	public void setIndex(int index) {
		this.index = index;
	}

	/**
	 * Returns the tile in which this site exists.
	 * @return the tile in which this site exists
	 */
	public Tile getTile(){
		return tile;
	}
	
	/**
	 * Sets the tile in which this site exists.
	 * @param location the tile location for this site
	 */
	public void setTile(Tile location){
		this.tile = location;
	}

	/**
	 * Returns the integer X value of the instance location
	 * (ex: SLICE_X5Y10, it will return 5).
	 * @return the X integer value of the site name or -1 if this instance is
	 * not placed or does not have X/Y coordinates in the site name
	 */
	public int getInstanceX(){
		return instanceX;
	}

	/**
	 * Returns the integer Y value of the instance location
	 * (ex: SLICE_X5Y10, it will return 10).
	 * @return The Y integer value of the site name or -1 if this instance is
	 * not placed or does not have X/Y coordinates in the site name
	 */
	public int getInstanceY(){
		return instanceY;
	}
	
	/**
	 * Sets the XY coordinates for this site based on the name.
	 * @param name the name of the site to infer and set the XY coordinates of.
	 */
	public boolean parseCoordinatesFromName(String name) {
		// reset the values
		this.instanceX = -1;
		this.instanceY = -1;

		// match the values
		Pattern re = Pattern.compile(".+_X(\\d+)Y(\\d+)");
		Matcher matcher = re.matcher(name);
		if (!matcher.matches())
			return false;

		// Populate the X and Y coordinates based on name
		this.instanceX = Integer.parseInt(matcher.group(1));
		this.instanceY = Integer.parseInt(matcher.group(2));
		return true;
	}

	/**
	 * Returns the current type of this site.
	 * @return the current type of this site
	 */
	public SiteType getType(){
		return getTemplate().getType();
	}

	/**
	 * Updates the type of this site to the specified type.
	 * <p/>
	 * This method obtains the site template from its device, therefore, the
	 * site must already exist in a tile which exists in a device.
	 * @param type the new type for this site
	 */
	public void setType(SiteType type) {
		if (!Arrays.asList(getPossibleTypes()).contains(type))
			throw new IllegalArgumentException("Invalid type: site=" + name + ", type=" + type);
		template = getTile().getDevice().getSiteTemplate(type);
	}

	/**
	 * Same as {@link #setType(SiteType)} except does not validate that the type is a
	 * legal type.
	 * @param type the new type for this site
	 */
	public void setTypeUnchecked(SiteType type) {
		template = getTile().getDevice().getSiteTemplate(type);
	}

	/**
	 * Returns the default type of this site.
	 * The default type is defined as getPossibleTypes[0].
	 *
	 * @return the default type of this site
	 */
	public SiteType getDefaultType() {
		if (possibleTypes == null)
			return template.getType();
		return possibleTypes[0];
	}

	/**
	 * Returns an array containing the valid types that this site can be
	 * treated as.
	 *
	 * @return the possible types for this site
	 */
	public SiteType[] getPossibleTypes() {
		return possibleTypes;
	}

	/**
	 * Sets the possible types for this site.
	 * The type as index 0 is considered the default type for the site.
	 * This method does not update the type of the site.
	 * @param possibleTypes the possible types for this site
	 */
	public void setPossibleTypes(SiteType[] possibleTypes) {
		this.possibleTypes = possibleTypes;
	}

	/**
	 * Returns the current template backing this site.
	 * The template will change when the site's type changes.
	 * @return the current template backing this site
	 */
	SiteTemplate getTemplate() {
		return template;
	}

	private SiteTemplate getTemplate(SiteType type) {
		if (getType() == type)
			return getTemplate();

		return getTile().getDevice().getSiteTemplate(type);
	}

	/**
	 * Returns whether the site is bonded.  IO are either bonded or unbonded.  Non-IO
	 * are always <code>internal</code>.
	 * @return the bondedness of the site
	 */
	public BondedType getBondedType() {
		return bondedType;
	}

	/**
	 * Sets whether the site is bonded.  IO are either bonded or unbonded.  Non-IO
	 * are always <code>internal</code>.
	 * @param bondedType the bondedness of the site
	 */
	public void setBondedType(BondedType bondedType) {
		this.bondedType = bondedType;
	}

	/* Exposed template getter methods */
	/**
	 * Returns a set containing all of the BEL names in the site.
	 * Unlike {@link #getBels()}, no objects are created by this call.
	 *
	 * @return set containing all of the BEL names in the site
	 */
	public Set<String> getBelNames() {
		return getBelNames(getTemplate());
	}

	public Set<String> getBelNames(SiteType type) {
		return getBelNames(getTemplate(type));
	}

	private Set<String> getBelNames(SiteTemplate template) {
		return template.getBelTemplates().keySet();
	}

	/**
	 * Returns the set of all BELs in the site.
	 * Use cautiously as Bel objects are recreated on each call.
	 * @return a new set, possibly empty, of all BELs in the site
	 * @see #getBelNames()
	 */
	public Set<Bel> getBels() {
		return getBels(getTemplate());
	}

	public Set<Bel> getBels(SiteType type) {
		return getBels(getTemplate(type));
	}

	private Set<Bel> getBels(SiteTemplate template) {
		Map<String, BelTemplate> belTemplates = template.getBelTemplates();
		if (belTemplates == null)
			return Collections.emptySet();

		return belTemplates.values().stream()
				.map(t -> new Bel(this, t))
				.collect(Collectors.toSet());
	}

	/**
	 * Returns the BEL of the specified name for the site.
	 * The Bel object will be created upon this call.
	 * @param belName the name of the BEL to return
	 * @return the BEL of the given name or null if no BEL with the specified name
	 *   exist in the (site, type) pair.
	 */
	public Bel getBel(String belName) {
		return getBel(getTemplate(), belName);
	}

	public Bel getBel(SiteType type, String belName) {
		return getBel(getTemplate(type), belName);
	}

	public Bel getBel(BelId belId) {
		return getBel(getTemplate(belId.getSiteType()), belId.getName());
	}

	private Bel getBel(SiteTemplate template, String belName) {
		BelTemplate bt = template.getBelTemplates().get(belName);
		if (bt == null)
			return null;
		return new Bel(this, bt);
	}

	/**
	 * Returns SiteTypes that are compatible with the default site type.
	 * Compatible types are different that possible types. These are types
	 * that the site type cannot be changed to, but instances of the type be
	 * placed on them.
	 *
	 * @return SiteTypes that are compatible with the default site type
	 */
	public SiteType[] getCompatibleTypes() {
		return getDefaultTemplate().getCompatibleTypes();
	}

	private SiteTemplate getDefaultTemplate() {
		return getTile().getDevice().getSiteTemplate(getDefaultType());
	}

	/**
	 * Returns the wires in the site which source wire connections.
	 *
	 * @return the wires in the site which source wire connections
	 */
	public Collection<Wire> getWires() {
		return getWires(getTemplate());
	}

	public Collection<Wire> getWires(SiteType type) {
		return getWires(getTemplate(type));
	}

	private Collection<Wire> getWires(SiteTemplate template) {
		return template.getWires().stream()
				.map(i -> new SiteWire(this, template.getType(), i))
				.collect(Collectors.toCollection(ArrayList::new));
	}

	/**
	 * Returns the wire in this site with the given name.  This method does not
	 * guarantee that the requested wire exists in this site.
	 * @param wireName the name of the wire to get
	 * @return the wire in this site with the given name
	 */
	public SiteWire getWire(String wireName) {
		return getWire(this.getType(), wireName);
	}

	/**
	 * Returns the wire in this site with the given name and type.  This method does
	 * not guarantee that the requested wire exists in this site.
	 * @param wireName the name of the wire to get
	 * @return the wire in this site with the given name
	 */
	// Included just for consistency with other methods.
	public SiteWire getWire(SiteType type, String wireName) {
		Device device = getTile().getDevice();
		Integer wireEnum = device.getWireEnumerator().getWireEnum(wireName);
		if (wireEnum == null)
			return null;
		return new SiteWire(this, type, wireEnum);
	}

	/**
	 * @param wireName name of wire to query for
	 * @return true if the wire with the given name exists in this site
	 */
	public boolean hasWire(String wireName) {
		return hasWire(getTemplate(), wireName);
	}

	/**
	 * @param wireName name of wire to query for
	 * @return true if the wire with the given name exists in this site configured
	 *   as [type]
	 */
	public boolean hasWire(SiteType type, String wireName) {
		return hasWire(getTemplate(type), wireName);
	}

	private boolean hasWire(SiteTemplate template, String wireName) {
		Device device = getTile().getDevice();
		Integer wireEnum = device.getWireEnumerator().getWireEnum(wireName);
		return wireEnum != null && template.getRouting().keySet().contains(wireEnum);
	}

	/**
	 * Returns the wire connections sourced by the specified wire.
	 *
	 * @param wire the source wire
	 * @return the wire connections sourced by the specified wire
	 */
	WireConnection[] getWireConnections(int wire) {
		return getWireConnections(getTemplate(), wire);
	}

	WireConnection[] getWireConnections(SiteType type, int wire) {
		return getWireConnections(getTemplate(type), wire);
	}

	private WireConnection[] getWireConnections(SiteTemplate template, int wire) {
		return template.getWireConnections(wire);
	}

	WireConnection[] getReverseConnections(int wire) {
		return getReverseConnections(getTemplate(), wire);
	}

	WireConnection[] getReverseConnections(SiteType type, int wire) {
		return getReverseConnections(getTemplate(type), wire);
	}

	private WireConnection[] getReverseConnections(SiteTemplate template, int wire) {
		return template.getReverseWireConnections(wire);
	}

	/**
	 * Returns the names of the source pins on the site.
	 * Unlike {@link #getSourcePins()}, this method will not dynamically create
	 * any objects.
	 * @return names of the source pins of the site
	 */
	public Set<String> getSourcePinNames() {
		return getSourcePinNames(getTemplate());
	}

	public Set<String> getSourcePinNames(SiteType type) {
		return getSourcePinNames(getTemplate(type));
	}

	private Set<String> getSourcePinNames(SiteTemplate template) {
		return template.getSources().keySet();
	}

	/**
	 * Creates and returns the source pins for this site.
	 * The SitePin objects are recreated upon each call.
	 * @return the source pins for this site
	 */
	public List<SitePin> getSourcePins() {
		return getSourcePins(getTemplate());
	}

	/**
	 * Creates and returns the source pins for this site when configured as type.
	 * The SitePin objects are recreated upon each call.
	 * @return the source pins for this site
	 */
	public List<SitePin> getSourcePins(SiteType type) {
		return getSourcePins(getTemplate(type));
	}
	
	private List<SitePin> getSourcePins(SiteTemplate template) {
		Map<String, SitePinTemplate> sourceTemplates = template.getSources();
		List<SitePin> pins = new ArrayList<>(sourceTemplates.size());
		for (SitePinTemplate pinTemplate : sourceTemplates.values()) {
			int externalWire = getExternalWire(template.getType(), pinTemplate.getName());
			pins.add(new SitePin(this, pinTemplate, externalWire));
		}
		return pins;
	}

	/**
	 * Creates and returns the source pin on this site with the specified name.
	 * @param pinName the name of the pin to create
	 * @return the source pin on this site with the specified name or
	 *   null if the pin does not exist
	 */
	public SitePin getSourcePin(String pinName) {
		return getSourcePin(getTemplate(), pinName);
	}

	/**
	 * Creates and returns the source pin on this site when configured as type with
	 * the specified name.
	 * @param pinName the name of the pin to create
	 * @return the source pin on this site with the specified name or
	 *   null if the pin does not exist
	 */
	public SitePin getSourcePin(SiteType type, String pinName) {
		return getSourcePin(getTemplate(type), pinName);
	}

	private SitePin getSourcePin(SiteTemplate template, String pinName) {
		SitePinTemplate pinTemplate = template.getSources().get(pinName);
		if (pinTemplate == null)
			return null;
		return new SitePin(this, pinTemplate, getExternalWire(template.getType(), pinName));
	}

	/**
	 * Returns the names of all sink pins on this site.
	 * Unlike {@link #getSinkPins()}, this method will not dynamically create
	 * any objects.

	 * @return the names of all sink pins on the site
	 */
	public Set<String> getSinkPinNames() {
		return getSinkPinNames(getTemplate());
	}

	/**
	 * Returns the names of all sink pins on this site when configured as the type.
	 * Unlike {@link #getSinkPins()}, this method will not dynamically create any
	 * objects.

	 * @return the names of all sink pins on the site
	 */
	public Set<String> getSinkPinNames(SiteType type) {
		return getSinkPinNames(getTemplate(type));
	}

	private Set<String> getSinkPinNames(SiteTemplate template) {
		return template.getSinks().keySet();
	}

	/**
	 * Creates and returns all sink pins on this site.
	 * The SitePin objects are recreated upon each call.
	 * @return all sink pins on this site
	 */
	public List<SitePin> getSinkPins() {
		return getSinkPins(getTemplate());
	}

	/**
	 * Creates and returns all sink pins on this site when configured as type.
	 * The SitePin objects are recreated upon each call.
	 * @return all sink pins on this site
	 */
	public List<SitePin> getSinkPins(SiteType type) {
		return getSinkPins(getTemplate(type));
	}

	private List<SitePin> getSinkPins(SiteTemplate template) {
		Map<String, SitePinTemplate> sinkTemplates = template.getSinks();
		List<SitePin> pins = new ArrayList<>(sinkTemplates.size());
		for (SitePinTemplate pinTemplate : sinkTemplates.values()) {
			int externalWire = getExternalWire(template.getType(), pinTemplate.getName());
			pins.add(new SitePin(this, pinTemplate, externalWire));
		}
		return pins;
	}

	/**
	 * Creates and returns the sink pin on this site with the specified name.
	 * @param pinName the name of the pin to create
	 * @return the sink pin on this site with the specified name or
	 *   null if the pin does not exist
	 */
	public SitePin getSinkPin(String pinName) {
		return getSinkPin(getTemplate(), pinName);
	}

	/**
	 * Creates and returns the sink pin on this site when configured as type with
	 * the specified name.
	 * @param pinName the name of the pin to create
	 * @return the sink pin on this site with the specified name or
	 *   null if the pin does not exist
	 */
	public SitePin getSinkPin(SiteType type, String pinName) {
		return getSinkPin(getTemplate(type), pinName);
	}

	private SitePin getSinkPin(SiteTemplate template, String pinName) {
		SitePinTemplate pinTemplate = template.getSinks().get(pinName);
		if (pinTemplate == null)
			return null;
		int externalWire = getExternalWire(template.getType(), pinName);
		return new SitePin(this, pinTemplate, externalWire);
	}

	/**
	 * Creates and returns the pin on this site with the specified name.
	 * @param pinName the name of the pin to create
	 * @return the pin on this site with the specified name
	 * @deprecated Use {@link #getPin(String pinName)} instead.
	 */
	@Deprecated
	public SitePin getSitePin(String pinName) {
		return getPin(pinName);
	}
	
	/**
	 * Creates and returns the pin on this site with the specified name.
	 * @param pinName the name of the pin to create
	 * @return the pin on this site with the specified name
	 */
	public SitePin getPin(String pinName) {
		return getPin(getTemplate(), pinName);
	}

	/**
	 * Creates and returns the pin on this site when configured as type with the
	 * specified name.
	 * @param pinName the name of the pin to create
	 * @return the pin on this site with the specified name
	 * @deprecated Use {@link #getPin(SiteType type, String pinName)} instead.
	 */
	@Deprecated
	public SitePin getSitePin(SiteType type, String pinName) {
		return getPin(type, pinName);
	}
	
	/**
	 * Creates and returns the pin on this site when configured as type with the
	 * specified name.
	 * @param pinName the name of the pin to create
	 * @return the pin on this site with the specified name
	 */
	public SitePin getPin(SiteType type, String pinName) {
		return getPin(getTemplate(type), pinName);
	}

	private SitePin getPin(SiteTemplate template, String pinName) {
		SitePinTemplate pinTemplate = template.getSinks().get(pinName);
		if (pinTemplate == null)
			pinTemplate = template.getSources().get(pinName);
		if (pinTemplate == null)
			return null;
		return new SitePin(this, pinTemplate, getExternalWire(template.getType(), pinName));
	}

	/**
	 * Creates and returns the pin on the site which connects to the specified
	 * external wire.
	 * @param wire the external wire
	 * @return the pin on the site which connects to the specified external wire or
	 *   null if the wire connects to no pins on this site
	 */
	SitePin getSitePinOfExternalWire(SiteType type, int wire) {
		SitePinTemplate pinTemplate = externalWireToPinMap.get(type).get(wire);
		if (pinTemplate == null)
			return null;
		int externalWire = getExternalWire(type, pinTemplate.getName());
		return new SitePin(this, pinTemplate, externalWire);
	}

	/**
	 * Creates and returns the pin on the site which connects to the specified
	 * internal wire.
	 * @param wire the internal wire
	 * @return the pin on the site which connects to the specified internal wire
	 *   or null if the wire connects to no pins on this site
	 */
	SitePin getSitePinOfInternalWire(SiteType type, int wire) {
		SiteTemplate template = getTemplate(type);
		Map<Integer, SitePinTemplate> internalWireToSitePinMap = template.getInternalWireToSitePinMap();
		SitePinTemplate pinTemplate = internalWireToSitePinMap.get(wire);
		if (pinTemplate == null)
			return null;
		int externalWire = getExternalWire(template.getType(), pinTemplate.getName());
		return new SitePin(this, pinTemplate, externalWire);
	}

	// Returns the wire which connects externally to the pin.  Needed to get from
	// inside the site back to the tile routing
	private int getExternalWire(SiteType type, String pinName) {
		return externalWires.get(type).get(pinName);
	}

	/**
	 * Returns the pin of the BEL in this site which connects to the specified wire.
	 * @param wire the site wire
	 * @return the pin of the BEL in this site which connects to the specified wire
	 */
	BelPin getBelPinOfWire(SiteType type, int wire) {
		SiteTemplate template = getTemplate(type);
		BelPinTemplate pinTemplate = template.getBelPins().get(wire);
		if (pinTemplate == null)
			return null;
		String belName = pinTemplate.getId().getName();
		Bel bel = getBel(template, belName);
		assert bel != null : "illegal device representation";
		return bel.getBelPin(pinTemplate.getName());
	}

	/**
	 * Sets the mapping of pin names to externally connected wires for each possible
	 * type this site can take.
	 * Used for device creation
	 * @param externalWires the mapping of pin names to externally connected wires
	 */
	public void setExternalWires(Map<SiteType, Map<String, Integer>> externalWires) {
		this.externalWires = externalWires;
	}

	/**
	 * Returns the mapping of pin names to externally connected wires for each
	 * possible type this site can take.
	 * @return the mapping of pin names to externally connected wires
	 */
	public Map<SiteType, Map<String, Integer>> getExternalWires() {
		return externalWires;
	}

	/**
	 * @deprecated Use {@link #getExternalWireToPinMap()} instead.
	 */
	@Deprecated
	public Map<SiteType, Map<Integer, SitePinTemplate>> getExternalWireToPinNameMap() {
		return getExternalWireToPinMap();
	}
	
	public Map<SiteType, Map<Integer, SitePinTemplate>> getExternalWireToPinMap() {
		return externalWireToPinMap;
	}
	
	/**
	 * Sets the mapping of wires to the names of the pins the wires connect to for
	 * each possible type this site can take.
	 * @param externalWireToPinMap the mapping of wires to pin names
	 * @deprecated Use {@link #setExternalWireToPinMap} instead.
	 */
	@Deprecated
	public void setExternalWireToPinNameMap(
		Map<SiteType, Map<Integer, SitePinTemplate>> externalWireToPinMap) {
		setExternalWireToPinMap(externalWireToPinMap);
	}

	/**
	 * Sets the mapping of wires to the names of the pins the wires connect to for
	 * each possible type this site can take.
	 * @param externalWireToPinMap the mapping of wires to pin names
	 */
	public void setExternalWireToPinMap(
		Map<SiteType, Map<Integer, SitePinTemplate>> externalWireToPinMap) {
		this.externalWireToPinMap = externalWireToPinMap;
	}

	// Site compatibility
	/**
	 * This method will check if the SiteType otherType can be placed
	 * at this site.  Most often only if they are
	 * equal can this be true.  However there are a few special cases that require
	 * extra handling.  For example a SLICEL can reside in a SLICEM site but not 
	 * vice versa.  
	 * @param otherType The site type to try to place on this site.
	 * @return True if otherType can be placed at this site, false otherwise.
	 */
	public boolean isCompatibleSiteType(SiteType otherType){
		for (SiteType compat : getCompatibleTypes())
			if (compat == otherType)
				return true;
		return false;
	}
	
	/**
	 * This method checks to see if the specified start and <br>
	 * end wire form a bel routethrough.
	 * @param startWire Start wire enum
	 * @param endWire Sink wire enum
	 * @return True if the wires form a routethrough
	 */
	boolean isRoutethrough(Integer startWire, Integer endWire) {
		return getTemplate().isRoutethrough(startWire, endWire);
	}

	boolean isRoutethrough(SiteType type, Integer startWire, Integer endWire) {
		return getTemplate(type).isRoutethrough(startWire, endWire);
	}

	/**
	 * This method gets the type of otherSite and calls the other method
	 * public boolean isCompatibleSiteType(SiteType otherType);
	 * See that method for more information.
	 * @param otherSite The other site to see if its type is compatible with this site.
	 * @return True if compatible, false otherwise.
	 */
	public boolean isCompatibleSiteType(Site otherSite){
		return isCompatibleSiteType(otherSite.getType());
	}

	@Override
	public String toString() {
		return "{Site " + name + "}";
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}

	/*
	   Class and method for optimized Hessian serialization.
	 */
	private static class SiteReplace implements Serializable {
		private static final long serialVersionUID = 3178000777471034057L;
		/** Name of the site with X and Y coordinates (ie. SLICE_X0Y0) */
		private String name;
		private SiteType[] possibleTypes;
		private Map<SiteType, Map<String, Integer>> externalWires;
		private BondedType bondedType;
		private Integer instanceX;
		private Integer instanceY;

		@SuppressWarnings("unused")
		private Site readResolve() {
			Site site = new Site();
			site.setName(name);
			site.possibleTypes = possibleTypes;
			site.externalWires = externalWires;
			site.bondedType = bondedType;
			
			if (instanceX != null || instanceY != null || !site.parseCoordinatesFromName(name)) {
				site.instanceX = (instanceX != null) ? instanceX : -1;
				site.instanceY = (instanceY != null) ? instanceY : -1;
			}
			
			return site;
		}
	}

	@SuppressWarnings("unused")
	private SiteReplace writeReplace() {
		SiteReplace repl = new SiteReplace();
		repl.name = name;
		repl.possibleTypes = possibleTypes;
		repl.externalWires = externalWires;
		repl.bondedType = bondedType;
		repl.instanceX = instanceX;
		repl.instanceY = instanceY;
		return repl;
	}
}
