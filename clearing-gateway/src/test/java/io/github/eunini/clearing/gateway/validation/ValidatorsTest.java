package io.github.eunini.clearing.gateway.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eunini.clearing.gateway.iso.AccountRef;
import io.github.eunini.clearing.iso.sample.SampleAccounts;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidatorsTest {

    @ParameterizedTest
    @ValueSource(strings = {"XB01USNY", "XB06GB2LXXX", "DEUTDEFF", "DEUTDEFF500"})
    void validBics(String bic) {
        assertThat(BicValidator.isValid(bic)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"XB01US", "xb01usny", "XB01QQNY", "XB01USNY12", "XB01US-Y"})
    void invalidBics(String bic) {
        assertThat(BicValidator.isValid(bic)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GB82WEST12345698765432", "DE89370400440532013000", "FR1420041010050500013M02606",
            "NL91ABNA0417164300", "CH9300762011623852957"})
    void publishedExampleIbansAreValid(String iban) {
        assertThat(IbanValidator.isValid(iban)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GB82WEST12345698765433", "GB82WEST1234569876543", "DE89370400440532013000X", "XX"})
    void corruptedIbansAreInvalid(String iban) {
        assertThat(IbanValidator.isValid(iban)).isFalse();
    }

    @Test
    void ibanCheckDigitsMatchIndependentGenerator() {
        var rng = new SplittableRandom(1);
        for (String country : new String[] {"GB", "DE", "FR", "NL", "CH", "BR"}) {
            for (int i = 0; i < 200; i++) {
                String iban = SampleAccounts.iban(country, "XB01" + country + "XX", rng);
                assertThat(IbanValidator.isValid(iban)).as(iban).isTrue();
                assertThat(IbanValidator.checkDigits(country, iban.substring(4))).isEqualTo(iban.substring(2, 4));
            }
        }
    }

    @Test
    void clabeCheckDigit() {
        // Widely published CLABE example (bank 032).
        assertThat(AccountRules.isValidClabe("032180000118359719", "032")).isTrue();
        assertThat(AccountRules.isValidClabe("032180000118359718", "032")).isFalse();
        assertThat(AccountRules.isValidClabe("032180000118359719", "002")).isFalse();
        var rng = new SplittableRandom(2);
        for (int i = 0; i < 500; i++) {
            assertThat(AccountRules.isValidClabe(SampleAccounts.clabe("923", rng), "923")).isTrue();
        }
    }

    @Test
    void accountRulesFollowTheAgentCountry() {
        var rng = new SplittableRandom(3);
        assertThat(AccountRules.isValid(new AccountRef(null, "123456789012"), "US", "XB01USNY")).isTrue();
        assertThat(AccountRules.isValid(new AccountRef(null, "12AB"), "US", "XB01USNY")).isFalse();
        assertThat(AccountRules.isValid(new AccountRef(null, "1234567"), "JP", "XB35JPJT")).isTrue();
        assertThat(AccountRules.isValid(new AccountRef(null, "12345678"), "JP", "XB35JPJT")).isFalse();
        // IBAN countries require an IBAN of that country.
        String gb = SampleAccounts.iban("GB", "XB06GB2L", rng);
        assertThat(AccountRules.isValid(new AccountRef(gb, null), "GB", "XB06GB2L")).isTrue();
        assertThat(AccountRules.isValid(new AccountRef(gb, null), "DE", "XB10DEFF")).isFalse();
        assertThat(AccountRules.isValid(new AccountRef(null, "12345678"), "GB", "XB06GB2L")).isFalse();
        assertThat(AccountRules.isValid(AccountRef.none(), "US", "XB01USNY")).isFalse();
    }

    @Test
    void currencyDecimalsFollowIso4217() {
        assertThat(CurrencyRules.toMinor(new BigDecimal("12.34"), "USD")).contains(1234L);
        assertThat(CurrencyRules.toMinor(new BigDecimal("12.340"), "EUR")).contains(1234L);
        assertThat(CurrencyRules.toMinor(new BigDecimal("12.345"), "EUR")).isEmpty();
        assertThat(CurrencyRules.toMinor(new BigDecimal("1500"), "JPY")).contains(1500L);
        assertThat(CurrencyRules.toMinor(new BigDecimal("1500.5"), "JPY")).isEmpty();
        assertThat(CurrencyRules.toMinor(BigDecimal.ZERO, "USD")).isEmpty();
        assertThat(CurrencyRules.toMinor(new BigDecimal("-1"), "USD")).isEqualTo(Optional.empty());
        assertThat(CurrencyRules.isSupported("XYZ")).isFalse();
        assertThat(CurrencyRules.supported()).hasSizeGreaterThanOrEqualTo(13);
    }
}
