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
package edu.byu.ece.rapidSmith.device.browser;


import com.trolltech.qt.core.Qt.MouseButton;
import com.trolltech.qt.core.Qt.PenStyle;
import com.trolltech.qt.gui.*;
import edu.byu.ece.rapidSmith.device.*;
import edu.byu.ece.rapidSmith.gui.NumberedHighlightedTile;
import edu.byu.ece.rapidSmith.gui.TileScene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;

/**
 * This class was written specifically for the DeviceBrowser class.  It
 * provides the scene content of the 2D tile array.
 */
public class DeviceBrowserScene extends TileScene{
	/**	 */
	Signal1<Tile> updateTile = new Signal1<>();
	/**	 */
	private QPen wirePen;
	/**	 */
	private ArrayList<QGraphicsLineItem> currLines;
	/**	 */
	private DeviceBrowser browser;
	/**	 */
	private Tile reachabilityTile;
	/**	 */
	private ArrayList<NumberedHighlightedTile> currentTiles = new ArrayList<>();


	DeviceBrowserScene(Device device, boolean hideTiles, boolean drawSites, DeviceBrowser browser){
		super(device, hideTiles, drawSites);
		currLines = new ArrayList<>();
		wirePen = new QPen(QColor.yellow, 0.25, PenStyle.SolidLine);
		this.browser = browser;
	}

	public void drawWire(Tile src, Tile dst){
		QGraphicsLineItem line = new QGraphicsLineItem(
				src.getColumn()*tileSize  + tileSize/2,
				src.getRow()*tileSize + tileSize/2,
				dst.getColumn()*tileSize + tileSize/2,
				dst.getRow()*tileSize + tileSize/2);
		line.setPen(wirePen);
		addItem(line);
	}

	void clearCurrentLines(){
		for(QGraphicsLineItem line : currLines){
			this.removeItem(line);
			line.dispose();
		}
		currLines.clear();
	}

	void drawWire(Tile src, int wireSrc, Tile dst, int wireDst){
		double enumSize = we.getWires().length;
		double x1 = (double) tileXMap.get(src)*tileSize  + (wireSrc%tileSize);
		double y1 = (double) tileYMap.get(src)*tileSize  + (wireSrc*tileSize)/enumSize;
		double x2 = (double) tileXMap.get(dst)*tileSize  + (wireDst%tileSize);
		double y2 = (double) tileYMap.get(dst)*tileSize  + (wireDst*tileSize)/enumSize;
		WireConnectionLine line = new WireConnectionLine(x1,y1,x2,y2, this, dst, wireDst);
		line.setToolTip(src.getName() + " " + we.getWireName(wireSrc) + " -> " +
				dst.getName() + " " + we.getWireName(wireDst));
		line.setPen(wirePen);
		line.setAcceptHoverEvents(true);
		addItem(line);
		currLines.add(line);
	}

	void drawConnectingWires(Tile tile, int wire){
		clearCurrentLines();
		if(tile == null) return;
		if(tile.getWireConnections(wire) == null) return;
		for(WireConnection w : tile.getWireConnections(wire)){
			drawWire(tile, wire, w.getTile(tile), w.getWire());
		}
	}

	private HashMap<Tile, Integer> findReachability(Tile t, Integer hops){
		HashMap<Wire, Integer> level = new HashMap<>();
		HashMap<Tile, Integer> reachabilityMap = new HashMap<>();

		Queue<Wire> queue = new LinkedList<>();
		for(Wire wire : t.getWires()){
			for(Connection c : wire.getWireConnections()){
				Wire w = c.getSinkWire();
				queue.add(w);
				level.put(w, 0);
			}
		}

		while(!queue.isEmpty()){
			Wire currWire = queue.poll();
			Integer i = reachabilityMap.get(currWire.getTile());
			if(i == null){
				i = 1;
				reachabilityMap.put(currWire.getTile(), i);
			}
			else{
				reachabilityMap.put(currWire.getTile(), i+1);
			}
			Integer lev = level.get(currWire);
			if(lev < hops-1){
				for(Connection c : currWire.getWireConnections()){
					Wire w = c.getSinkWire();
					queue.add(w);
					level.put(w, lev+1);
				}
			}
		}
		return reachabilityMap;
	}

	private void drawReachability(HashMap<Tile, Integer> map){
		menuReachabilityClear();
		for(Tile t : map.keySet()){
			int color = map.get(t)*16 > 255 ? 255 : map.get(t)*16;
			NumberedHighlightedTile tile = new NumberedHighlightedTile(t, this, map.get(t));
			tile.setBrush(new QBrush(new QColor(0, color, 0)));
			currentTiles.add(tile);
		}
	}

	@SuppressWarnings("unused")
	private void menuReachability1(){
		drawReachability(findReachability(reachabilityTile, 1));
	}

	@SuppressWarnings("unused")
	private void menuReachability2(){
		drawReachability(findReachability(reachabilityTile, 2));
	}

	@SuppressWarnings("unused")
	private void menuReachability3(){
		drawReachability(findReachability(reachabilityTile, 3));
	}

	@SuppressWarnings("unused")
	private void menuReachability4(){
		drawReachability(findReachability(reachabilityTile, 4));
	}

	@SuppressWarnings("unused")
	private void menuReachability5(){
		drawReachability(findReachability(reachabilityTile, 5));
	}

	private void menuReachabilityClear(){
		for(NumberedHighlightedTile rect : currentTiles){
			rect.remove();
		}
		currentTiles.clear();
	}


	@Override
	public void mouseDoubleClickEvent(QGraphicsSceneMouseEvent event){
		Tile t = getTile(event);
		this.updateTile.emit(t);
		super.mouseDoubleClickEvent(event);
	}

	@Override
	public void mouseReleaseEvent(QGraphicsSceneMouseEvent event){
		if(event.button().equals(MouseButton.RightButton)){
			if(browser.view.hasPanned){
				browser.view.hasPanned = false;

			}
			else{
				reachabilityTile = getTile(event);
				QMenu menu = new QMenu();
				QAction action1 = new QAction("Draw Reachability (1 Hop)", this);
				QAction action2 = new QAction("Draw Reachability (2 Hops)", this);
				QAction action3 = new QAction("Draw Reachability (3 Hops)", this);
				QAction action4 = new QAction("Draw Reachability (4 Hops)", this);
				QAction action5 = new QAction("Draw Reachability (5 Hops)", this);
				QAction actionClear = new QAction("Clear Highlighted Tiles", this);
				action1.triggered.connect(this, "menuReachability1()");
				action2.triggered.connect(this, "menuReachability2()");
				action3.triggered.connect(this, "menuReachability3()");
				action4.triggered.connect(this, "menuReachability4()");
				action5.triggered.connect(this, "menuReachability5()");
				actionClear.triggered.connect(this, "menuReachabilityClear()");
				menu.addAction(action1);
				menu.addAction(action2);
				menu.addAction(action3);
				menu.addAction(action4);
				menu.addAction(action5);
				menu.addAction(actionClear);
				menu.exec(event.screenPos());
			}
		}


		super.mouseReleaseEvent(event);
	}
}
