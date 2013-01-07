/*
�Copyright 2012 Nick Malleson
This file is part of RepastCity.

RepastCity is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

RepastCity is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with RepastCity.  If not, see <http://www.gnu.org/licenses/>.
*/

package repastcity3.environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import repast.simphony.space.gis.Geography;
import repastcity3.exceptions.EnvironmentError;
import repastcity3.main.ContextManager;
import repastcity3.main.GlobalVars;
import repastcity3.main.Resetable;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.index.SpatialIndex;
import com.vividsolutions.jts.index.strtree.STRtree;
import com.vividsolutions.jts.operation.distance.DistanceOp;

/**
 * Class that can be used to hold spatial indexes for Geography Projections. This
 * allows for more efficient GIS operations (e.g. finding nearby objects). The inner
 * <code>Index</code> class is used to actually hold the index.
 * 
 * @author Nick Malleson
 * @see SpatialIndex
 * @see STRtree
 */
public abstract class SpatialIndexManager {
	
	// Link spatial indices to their geographies.
	private static Map<Geography<?>, Index<?>> indices = new HashMap<Geography<?>, Index<?>>();
	
	/**
	 * Create a new spatial index for the given geography <code>Geography</code>.  
	 * @param geog 
	 * @param clazz The class of the objects that are being stored in the geography.
	 * @param <T> The type of object stored in the geography.
	 * @throws EnvironmentError if the geography already has a spatial index.
	 */
	public static <T> void createIndex(Geography<T> geog, Class<T> clazz) throws EnvironmentError {		
		Index<T> i = new Index<T>(geog, clazz);
		// See if the geography already has an index
		if (indices.containsKey(geog)) {
			throw new EnvironmentError("The geography "+geog.toString()+" already has a spatial index.");
		}
		SpatialIndexManager.indices.put(geog, i);
	}
	
	/**
	 * Find the nearest object in the given geography to the coordinate.
	 * 
	 * @param <T> The type of object that will be returned.
	 * @param x
	 *            The coordinate to search around
	 * @param geography
	 *            The given geography to look through
	 * @param closestPoints
	 *            An optional List that will be populated with the closest
	 *            points to x (i.e. the results of
	 *            <code>distanceOp.closestPoints()</code>.
	 * @return The nearest object.
	 * @throws NoSuchElementException
	 *             If there is no spatial index for the given geography.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T findNearestObject(Geography<T> geog, Coordinate x, List<Coordinate> closestPoints,
			GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE searchDist) 
		throws NoSuchElementException {
		
		// NOTE: this method used to be synchronized, but I can't see why, maybe not thread safe?
		
		
		Index<T> index = (Index<T>) indices.get(geog);
		if (index==null) {
			throw new NoSuchElementException("The geometry "+geog.getName()+" does not have a spatial index.");
		}
		
		Point p = new GeometryFactory().createPoint(x);
		
		// Query the spatial index for the nearest objects.
		List<Geometry> close = index.si.query(p.getEnvelope().buffer(searchDist.dist).getEnvelopeInternal());
		assert close != null && close.size() > 0 : "For some reason the spatial index query hasn't found any obejects " +
				"close to the given coordinate "+x.toString();
		
		// Now go through and find the closest one.
		DistanceOp distOp;
		double minDist = Double.MAX_VALUE;
		Geometry nearestGeom = null;
		for (Geometry g:close) {
			distOp = new DistanceOp(p, g);
			double thisDist = distOp.distance();
			if (thisDist < minDist) {
				minDist = thisDist;
				nearestGeom = g;
				// Optionally record the closest points
				if (closestPoints != null) {
					closestPoints.clear();
					closestPoints.addAll(Arrays.asList(distOp.closestPoints()));
				}
			} // if thisDist < minDist
		} // for nearRoads
		
		assert nearestGeom != null : "Internal error: could not find the closest geometry from the list of " +
				close.size()+" close objects.";
		
		T nearestObject = index.lookupFeature(nearestGeom);
		return nearestObject;
	}
	
	
	/**
	 * Find the object at the given coordinate.
	 * 
	 * @param <T> The type of object that will be returned.
	 * @param x
	 *            The coordinate to search around
	 * @param geography
	 *            The given geography to look through
	 * @return The object at the given coordinates or null if there isn't one there.
	 * @throws NoSuchElementException
	 *             If there is no spatial index for the given geography.
	 * @throws Exception 
	 *             If more or less than 1 objects are found at the coordinate (for want of a more appropriate exception!).
	 */
	@SuppressWarnings("unchecked")
	public static <T> T findObjectAt(Geography<T> geog, Point p, 
			GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE searchDist) throws NoSuchElementException, Exception {

		Index<T> index = (Index<T>) indices.get(geog);
		if (index==null) {
			throw new NoSuchElementException("The geometry "+geog.getName()+" does not have a spatial index.");
		}
		
		
		// Query the spatial index for the nearest objects.
		List<Geometry> close = index.si.query(p.getEnvelope().buffer(searchDist.dist).getEnvelopeInternal());
		assert close != null && close.size() > 0 : "For some reason the spatial index query hasn't found any obejects " +
				"close to the given coordinate "+p.toString();
		
		if (close==null || close.size()<1) {
			throw new Exception("No objects found at point "+p.toString());
		}
		
		// Now go through and find the one object that the coordinate is within
		Geometry touching = null;
		for (Geometry g:close) {
			if (p.within(g)) {
				if (touching != null) {
					throw new Exception("More than one object found at the point " +p.toString());
				}
				touching = g;
			}
		}
		
		// Now find the object associated with the geometry
		T theObject = index.lookupFeature(touching);
		return theObject;
	}
	
	/**
	 * Wrapper for the other findObjectAt() function which allows a Coordinate to be passed rather than a Point.
	 */
	public static <T> T findObjectAt(Geography<T> geog, Coordinate x, 
			GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE searchDist) throws NoSuchElementException, Exception {
		
		Point p = new GeometryFactory().createPoint(x);
		return SpatialIndexManager.findObjectAt(geog, p, searchDist);
	}
	

	
	/**
	 * Search for objects located at the given coordinate. This uses the <code>query()</code> function of the
	 * underlying spatial index so might return objects that are close two, but do not interesect, the coordinate. 
	 * 
	 * @param <T> The type of object that will be returned.
	 * @param x
	 *            The location to search around
	 * @param geography
	 *            The given geography to look through
	 * @return The objects that intersect the coordinate (or are close to it) or an empty list if none could be found. 
	 * @throws NoSuchElementException
	 *             If there is no spatial index for the given geography.
	 * @see STRtree
	 */
	@SuppressWarnings("unchecked")
	public static  <T> List<T> search(Geography<T> geog, Geometry geom) throws NoSuchElementException {
		
		// NOTE: this method used to be synchronized, but I can't see why, maybe not thread safe?
		
		Index<T> index = (Index<T>) indices.get(geog);
		if (index==null) {
			throw new NoSuchElementException("The geometry "+geog.getName()+" does not have a spatial index.");
		}
		
		// Query the spatial index for the nearest objects.
		List<Geometry> close = index.si.query(geom.getEnvelopeInternal());
		List<T> objects = new ArrayList<T>();
		for (Geometry g:close) {
			objects.add(index.lookupFeature(g));
		}
		return objects;
	}
	
	/**
	 * Find out whether or not this <code>SpatialIndexManager</code> has an index for the
	 * given geography.
	 */
	public static boolean hasIndex(Geography<?> geog) {
		return indices.containsKey(geog);
	}

	public static void clearIndices() {
		SpatialIndexManager.indices.clear();
		SpatialIndexManager.indices = new HashMap<Geography<?>, Index<?>>(); // (probably not necessary)
	}
	
}

/**
 * Inner class used for convenience to store each spatial index as well as other useful information
 * (e.g. maintain a link between the geometries in the index and the actual objects that these
 * geometries belong to.
 * 
 * @author Nick Malleson
 *
 * @param <T> The type of object being stored (e.g. House, Road, etc).
 */
class Index <T> {
	
	/*
	 * The actual spatial index.
	 */
	SpatialIndex si;
	/*
	 * A lookup relating Geometrys to the objects that they represent.  
	 */
	private Map<Geometry, T> featureLookup;
	
	public Index(Geography<T> geog, Class<T> clazz) {
		this.si = new STRtree();
		this.featureLookup = new HashMap<Geometry, T>();
		this.createIndex(geog, clazz);		
	}
	
	// Run through each object in the geography and add them to the spatial index.
	private void createIndex(Geography<T> geog, Class<T> clazz) {
		Geometry geom;
		Envelope bounds;
		for (T t:geog.getAllObjects()) {
            geom = (Geometry) geog.getGeometry(t);
            bounds = geom.getEnvelopeInternal();
            this.si.insert(bounds, geom);
            this.featureLookup.put(geom, t);
		}
	}
	
	public T lookupFeature (Geometry geom) throws NoSuchElementException {
		assert this.featureLookup.containsKey(geom) : "Internal error: for some reason the " +
				"given geometry is not a key in the feature lookup table.";
		return this.featureLookup.get(geom);
	}
}
