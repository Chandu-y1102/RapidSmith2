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
package design.rscpImport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoEdifInterface;
import edu.byu.ece.rapidSmith.util.Exceptions;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This class is used to test the {@link VivadoEdifInterface}. Add tests as necessary.
 */
public class EdifTests {

	@Test
	@DisplayName("Parse Exception")
	public void exceptionTest() {
		VivadoEdifInterface vivadoEdifInterface = new VivadoEdifInterface();
		assertThrows(Exceptions.ParseException.class, () -> vivadoEdifInterface.parseEdif("bogusEdifFile.edf", null, null));
	}
}
