/*
 * Copyright (c) 2016 Vivid Solutions.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * and Eclipse Distribution License v. 1.0 which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Modified for AutoParkingPlan to follow GEOS 3.14.1 (src/operation/polygonize): edges and directed edges are kept
 * in insertion order (JTS PlanarGraph uses HashSet, which makes ring start vertices depend on identity hash codes)
 * and shells are not re-sorted by envelope.
 */
package com.autoparkingplan.parking.geom.polygonize;

import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.planargraph.Edge;

/**
 * An edge of a polygonization graph.
 *
 * @version 1.7
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class PolygonizeEdge
    extends Edge
{
  private LineString line;

  public PolygonizeEdge(LineString line)
  {
    this.line = line;
  }
  public LineString getLine() { return line; }
}
