/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Actual optional place-name parser, with literal generated JSON and no transport or UI. */
class PhotoCaptionPlaceNullTest {
  @Test fun explicitNullCityUsesLocality() {
    assertEquals("Dublin, Ireland", PhotoCaption.parsePlace(
        """{"city":null,"locality":"Dublin","countryName":"Ireland"}"""))
  }

  @Test fun explicitNullCityAndLocalityUseRegion() {
    assertEquals("Tuscany, Italy", PhotoCaption.parsePlace(
        """{"city":null,"locality":null,"principalSubdivision":"Tuscany","countryName":"Italy"}"""))
  }

  @Test fun explicitNullCountryDoesNotAppearAfterCity() {
    assertEquals("Paris", PhotoCaption.parsePlace(
        """{"city":"Paris","countryName":null}"""))
  }

  @Test fun allExplicitNullNamesHaveNoPlaceLabel() {
    assertNull(PhotoCaption.parsePlace(
        """{"city":null,"locality":null,"principalSubdivision":null,"countryName":null}"""))
  }

  @Test fun ordinaryCityBeatsLocalityAndRegion() {
    assertEquals("Paris, France", PhotoCaption.parsePlace(
        """{"city":"Paris","locality":"Versailles","principalSubdivision":"Ile-de-France","countryName":"France"}"""))
  }

  @Test fun ordinaryLocalityIsUsedWithoutCity() {
    assertEquals("Townsville, Australia", PhotoCaption.parsePlace(
        """{"locality":"Townsville","principalSubdivision":"Queensland","countryName":"Australia"}"""))
  }

  @Test fun absentCityLocalityAndRegionRetainCountry() {
    assertEquals("France", PhotoCaption.parsePlace("""{"countryName":"France"}"""))
  }

  @Test fun literalNullStringRemainsAPlaceName() {
    assertEquals("null, Ireland", PhotoCaption.parsePlace(
        """{"city":"null","countryName":"Ireland"}"""))
  }
}
