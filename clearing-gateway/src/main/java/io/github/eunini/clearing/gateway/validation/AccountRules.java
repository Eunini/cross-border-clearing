package io.github.eunini.clearing.gateway.validation;

import io.github.eunini.clearing.gateway.iso.AccountRef;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Per-country account identifier rules. These are deliberately simplified
 * stand-ins for national formats:
 *
 * <ul>
 *   <li>IBAN countries (GB, DE, FR, NL, CH, BR): a valid IBAN with the agent's country prefix.</li>
 *   <li>MX: 18-digit CLABE with its weighted (3, 7, 1) check digit, whose first three
 *       digits must be the agent's bank code. Simulated Mexican participants use the
 *       fictional bank code "9" + the two digits of their BIC prefix (XB23MXMM -&gt; 923).</li>
 *   <li>Other countries: digits only, with a country-specific length range.</li>
 * </ul>
 */
public final class AccountRules {

    private static final Map<String, Pattern> DOMESTIC = Map.of(
            "US", Pattern.compile("\\d{4,17}"),
            "CA", Pattern.compile("\\d{7,12}"),
            "IN", Pattern.compile("\\d{9,18}"),
            "SG", Pattern.compile("\\d{9,12}"),
            "HK", Pattern.compile("\\d{9,12}"),
            "JP", Pattern.compile("\\d{7}"),
            "AU", Pattern.compile("\\d{6,10}"),
            "CN", Pattern.compile("\\d{16,19}"));

    private static final int[] CLABE_WEIGHTS = {3, 7, 1};

    private AccountRules() {}

    public static boolean isValid(AccountRef account, String agentCountry, String agentBic) {
        if (account == null || !account.present()) {
            return false;
        }
        if (IbanValidator.LENGTHS.containsKey(agentCountry)) {
            return account.iban() != null
                    && account.iban().startsWith(agentCountry)
                    && IbanValidator.isValid(account.iban());
        }
        String id = account.other();
        if (id == null) {
            return false;
        }
        if ("MX".equals(agentCountry)) {
            return isValidClabe(id, clabeBankCode(agentBic));
        }
        Pattern p = DOMESTIC.get(agentCountry);
        return p != null && p.matcher(id).matches();
    }

    public static String clabeBankCode(String bic) {
        return "9" + bic.substring(2, 4);
    }

    public static boolean isValidClabe(String clabe, String bankCode) {
        if (clabe == null || !clabe.matches("\\d{18}") || bankCode == null || !clabe.startsWith(bankCode)) {
            return false;
        }
        return clabeCheckDigit(clabe.substring(0, 17)) == clabe.charAt(17) - '0';
    }

    /** CLABE check digit: weights 3, 7, 1 repeating, each product taken mod 10. */
    public static int clabeCheckDigit(String first17) {
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += ((first17.charAt(i) - '0') * CLABE_WEIGHTS[i % 3]) % 10;
        }
        return (10 - sum % 10) % 10;
    }
}
