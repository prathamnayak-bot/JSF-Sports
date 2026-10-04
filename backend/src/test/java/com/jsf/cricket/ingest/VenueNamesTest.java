package com.jsf.cricket.ingest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class VenueNamesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Wankhede Stadium, Mumbai                              | Wankhede Stadium",
            "Wankhede Stadium                                      | Wankhede Stadium",
            "MA Chidambaram Stadium, Chepauk, Chennai              | MA Chidambaram Stadium",
            "M.Chinnaswamy Stadium                                 | M Chinnaswamy Stadium",
            "M Chinnaswamy Stadium, Bengaluru                      | M Chinnaswamy Stadium",
            "Feroz Shah Kotla                                      | Arun Jaitley Stadium",
            "Sardar Patel Stadium, Motera                          | Narendra Modi Stadium",
            "Punjab Cricket Association Stadium, Mohali            | Punjab Cricket Association IS Bindra Stadium",
            "Dr. Y.S. Rajasekhara Reddy ACA-VDCA Cricket Stadium   | Dr Y S Rajasekhara Reddy ACA-VDCA Cricket Stadium",
    })
    void normalisesSpellingsAndRenames(String inFile, String expected) {
        assertThat(CricsheetImporter.canonicalVenue(inFile)).isEqualTo(expected);
    }
}
