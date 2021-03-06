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

import edu.byu.ece.rapidSmith.device.Connection.ReverseTileWireConnection;
import edu.byu.ece.rapidSmith.device.Connection.TileWireConnection;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A wire inside a tile but outside a site.  This is part of the general
 * routing circuitry.  TileWires are composed of the tile the wire exists in
 * and the enumeration identifying the individual wire.
 */
public class TileWire implements Wire, Serializable {

	private static final long serialVersionUID = 5844788118958981887L;
	private Tile tile;
	private int wire;
	
	public TileWire(Tile tile, int wire) {
		assert tile != null;

		this.tile = tile;
		this.wire = wire;
	}

	@Override
	public Tile getTile() {
		return tile;
	}

	/**
	 * @deprecated Use {@link #getName} instead.
	 */
	@Override
	@Deprecated
	public String getWireName() {
		return getName();
	}
	
	/**
	 * @deprecated Use {@link #getFullName} instead.
	 */
	@Override
	@Deprecated
	public String getFullWireName() {
		return getFullName();
	}
	
	@Override
	public String getName() {
		return tile.getDevice().getWireEnumerator().getWireName(wire);
	}

	@Override
	public String getFullName() {
		return getTile().getName() + "/" + getName();
	}
	
	/**
	 * Always returns null.
	 */
	@Override
	public Site getSite() {
		return null;
	}

	@Override
	public int getWireEnum() {
		return wire;
	}

	/**
	 * Returns all sink connections within and between tiles.
	 */
	@Override
	public Collection<Connection> getWireConnections() {	
		WireConnection[] wireConnections = tile.getWireConnections(wire);
		if (wireConnections == null)
			return Collections.emptyList();
		
		return Arrays.stream(wireConnections)
				.map(wc -> new TileWireConnection(this, wc))
				.collect(Collectors.toList());
	}
	
	public WireConnection[] getWireConnectionsArray(){
		return tile.getWireConnections(wire);
	}

	/**
	 * Returns the connection to the given sink wire, if it exists.
	 * @param sinkWire the sink wire to get a connection to
	 * @return the wire connection to sinkWire
	 */
	@Override
	public Connection getWireConnection(Wire sinkWire) {
		WireConnection[] wireConnections = tile.getWireConnections(wire);
		if (wireConnections == null)
			return null;

		return Arrays.stream(wireConnections)
				.map(wc -> new TileWireConnection(this, wc))
				.filter(wc -> wc.getSinkWire().equals(sinkWire)).iterator().next();
	}

	/**
	 * Gets wires that are part of the same node.
	 * Only includes start and end wires (intermediate wires aren't included)
	 * @return a set including the wires of the node
	 */
	@Override
	public Set<Wire> getWiresInNode() {
		Set<Wire> wiresInNode = new HashSet<>();
		wiresInNode.add(this);
		Collection<Connection> directForwardConnections = getWireConnections().stream()
				.filter(connection -> !connection.isPip()).collect(Collectors.toList());
		Collection<Connection> directReverseConnections = getReverseWireConnections().stream()
				.filter(connection -> !connection.isPip()).collect(Collectors.toList());

		for (Connection conn : directForwardConnections) {
			wiresInNode.add(conn.getSinkWire());
		}
		for (Connection conn : directReverseConnections) {
			wiresInNode.add(conn.getSinkWire());
		}

		return wiresInNode;
	}

	@Override
	public Collection<SitePin> getAllConnectedPins() {
		Collection<SitePin> sitePins = tile.getSitePinsOfWire(this.wire);
		return sitePins.stream().filter(it -> !it.isInput())
			.collect(Collectors.toSet());
	}

	/**
	 * Returns the connection into a primitive site that this wire drives.
	 */
	@Override
	public SitePin getConnectedPin() {
		SitePin sitePin = tile.getSitePinOfWire(this.wire);
		if (sitePin == null || !sitePin.isInput())
			return null;
		return sitePin;
	}

	/**
	 * Always returns null.
	 */
	@Override
	public BelPin getTerminal() {
		return null;
	}

	/**
	 * Returns connection to all connections within or between tiles which drive
	 * this wire.
	 */
	@Override
	public Collection<Connection> getReverseWireConnections() {
		WireConnection[] wireConnections = tile.getReverseConnections(wire);
		if (wireConnections == null)
			return Collections.emptyList();

		return Arrays.stream(wireConnections)
				.map(wc -> new ReverseTileWireConnection(this, wc))
				.collect(Collectors.toList());
	}
	
	public WireConnection[] getReverseWireConnectionsArray() {
		return tile.getReverseConnections(wire);
	}

	@Override
	public Collection<SitePin> getAllReverseSitePins() {
		Collection<SitePin> sitePins = tile.getSitePinsOfWire(this.wire);
		return sitePins.stream().filter(it -> !it.isOutput())
			.collect(Collectors.toSet());
	}

	/**
	 * Returns the site pin connection driving this wire.
	 */
	@Override
	public SitePin getReverseConnectedPin() {
		SitePin sitePin = tile.getSitePinOfWire(this.wire);
		if (sitePin == null || !sitePin.isOutput())
			return null;
		return sitePin;
	}

	/**
	 * Always returns null.
	 */
	@Override
	public BelPin getSource() {
		return null;
	}

	/**
	 * Tests if the object is equal to this wire.  Wires are equal if they share
	 * the same tile and wire enumeration.
	 * @return true if <i>obj</i> is the same wire as this wire
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null || getClass() != obj.getClass()) {
			return false;
		}
		TileWire other = (TileWire) obj;
		return Objects.equals(this.tile, other.tile)
				&& this.wire == other.wire;
	}

	@Override
	public int hashCode() {
		return wire * 31 + tile.hashCode();
	}

	@Override
	public String toString() {
		return tile.getName() + " " + tile.getDevice().getWireEnumerator().getWireName(wire);
	}
}
