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

package edu.byu.ece.rapidSmith.device.xdlrc;

/**
 *  Convenience class to report the current progress of ongoing parsing.
 */
public class XDLRCParseProgressListener extends XDLRCParserListener {

	private int totalTiles;
	private int tilesParsed;

	public XDLRCParseProgressListener() {
		this.totalTiles = 0;
		this.tilesParsed = 0;
	}

	@Override
	protected void enterXdlResourceReport(pl_XdlResourceReport tokens) {
		this.totalTiles = 0;
		this.tilesParsed = 0;
	}

	/**
	 * Just print a line.
	 */
	@Override
	protected void exitXdlResourceReport(pl_XdlResourceReport tokens) {
		System.out.println();
	}

	@Override
	protected void enterTiles(pl_Tiles tokens) {
		totalTiles = tokens.rows * tokens.columns;
		System.out.println(String.format("Parsing tile %6d of %6d tiles.", tilesParsed, totalTiles));
	}

	@Override
	protected void enterTile(pl_Tile tokens) {
		// print out a heartbeat to the console every 1000 tiles
		if (++tilesParsed % 1000 == 0) {
			System.out.println(String.format("Parsing tile %6d of %6d tiles.", tilesParsed, totalTiles));
		}
	}
}
