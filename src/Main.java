import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Single-file portfolio valuator that:
 * - reads Investments.csv, Quotes.csv, Transactions.csv (semicolon-separated)
 * - expects CSVs in working directory or pass absolute paths via args[0..2]
 * - reads investorId and referenceDate either from args or interactively

 * Usage:
 * javac Main.java
 * java Main [investorId] [YYYY-MM-DD] [pathToCsvDirOptional]

 * If investorId/date not provided, program will prompt.
 */

// Models
static class Investment {
    String investorId;    // owner in Investments.csv
    String investmentId;
    String type;          // e.g. "Fonds"
    String isin;          // for stocks
    String city;
    String fondsInvestor; // if this investment is inside a fund, this column contains the parent fund id (otherwise blank)

    public Investment(String investorId, String investmentId, String type, String isin, String city, String fondsInvestor) {
        this.investorId = investorId;
        this.investmentId = investmentId;
        this.type = type;
        this.isin = isin;
        this.city = city;
        this.fondsInvestor = fondsInvestor;
    }
}

static class Transaction {
    String investmentId;
    String type; // Shares | Estate | Building | Percentage (case-insensitive)
    BigDecimal value; // stored as decimal (shares or absolute or percent)
    LocalDate date;

    public Transaction(String investmentId, String type, BigDecimal value, LocalDate date) {
        this.investmentId = investmentId;
        this.type = type;
        this.value = value;
        this.date = date;
    }
}

static class Quote {
    String isin;
    LocalDate date;
    BigDecimal pricePerShare;

    public Quote(String isin, LocalDate date, BigDecimal pricePerShare) {
        this.isin = isin;
        this.date = date;
        this.pricePerShare = pricePerShare;
    }
}

// Repositories (preserve insertion order)
static Map<String, List<Investment>> investmentsByInvestor = new HashMap<>();
static LinkedHashMap<String, Investment> investments = new LinkedHashMap<>();
static LinkedHashMap<String, List<Transaction>> transactionsByInvestment = new LinkedHashMap<>();
static LinkedHashMap<String, List<Quote>> quotesByISIN = new LinkedHashMap<>();

static DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

static void main(String[] args) throws Exception {
    // paths (allow optional directory arg)
    String dir = args.length >= 3 ? args[2] : ".";
    Path investmentsPath = Paths.get(dir, "Investments.csv");
    Path quotesPath = Paths.get(dir, "Quotes.csv");
    Path transactionsPath = Paths.get(dir, "Transactions.csv");

    loadInvestments(investmentsPath);
    loadQuotes(quotesPath);
    loadTransactions(transactionsPath);

    String investorId;
    LocalDate refDate;

    if (args.length >= 2) {
        investorId = args[0];
        refDate = LocalDate.parse(args[1], DATE_FMT);
    } else {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        System.out.print("Enter investor id (e.g. Investor0): ");
        investorId = br.readLine().trim();
        System.out.print("Enter reference date (YYYY-MM-DD): ");
        refDate = LocalDate.parse(br.readLine().trim(), DATE_FMT);
    }

    Map<String, BigDecimal> breakdown = computePortfolioForInvestorOnDate(investorId, refDate);

    // Print breakdown and total
    System.out.println("\nPortfolio evaluation for " + investorId + " on " + refDate);

    BigDecimal total = BigDecimal.ZERO;
    for (Map.Entry<String, BigDecimal> e : breakdown.entrySet()) {
        System.out.println(String.format("  %-20s -> %12s", e.getKey(), formatMoney(e.getValue())));
        total = total.add(e.getValue());
    }

    System.out.println("---------------------------------------------------");
    System.out.println(" TOTAL                            -> " + formatMoney(total));
}

// Format money with 2 decimals
private static String formatMoney(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
}

// Load Investments.csv (semicolon separated)
private static void loadInvestments(Path p) throws IOException {
    try (BufferedReader br = Files.newBufferedReader(p)) {
        String header = br.readLine(); // header
        String line;

        while ((line = br.readLine()) != null) {
            String[] parts = line.split(";", -1);
            String investorId = safe(parts, 0);
            String investmentId = safe(parts, 1);
            String type = safe(parts, 2);
            String isin = safe(parts, 3);
            String city = safe(parts, 4);
            String fondsInvestor = safe(parts, 5);

            if (investmentId.isEmpty()) continue;

            Investment inv = new Investment(investorId, investmentId, type, isin, city, fondsInvestor);
            investments.put(investmentId, inv);
            investmentsByInvestor
                    .computeIfAbsent(inv.investorId, x -> new ArrayList<>())
                    .add(inv);
        }
    }
}

// Load Quotes.csv (ISIN;Date;PricePerShare)
private static void loadQuotes(Path p) throws IOException {
    try (BufferedReader br = Files.newBufferedReader(p)) {
        String header = br.readLine();
        String line;

        while ((line = br.readLine()) != null) {
            String[] parts = line.split(";", -1);
            String isin = safe(parts, 0);
            LocalDate date = LocalDate.parse(safe(parts, 1), DATE_FMT);
            BigDecimal price = new BigDecimal(safe(parts, 2));
            Quote q = new Quote(isin, date, price);
            quotesByISIN.computeIfAbsent(isin, k -> new ArrayList<>()).add(q);
        }

        // sort each quote list by date in ascending order
        for (List<Quote> lst : quotesByISIN.values()) {
            lst.sort(Comparator.comparing(o -> o.date));
        }
    }
}

// Load Transactions.csv (InvestorId;InvestmentId;Type;Value;Date)
private static void loadTransactions(Path p) throws IOException {
    try (BufferedReader br = Files.newBufferedReader(p)) {
        String header = br.readLine();
        String line;

        while ((line = br.readLine()) != null) {
            String[] parts = line.split(";", -1);
            String investmentId = safe(parts, 0);
            String type = safe(parts, 1);
            LocalDate date = LocalDate.parse(safe(parts, 2), DATE_FMT);
            BigDecimal value = new BigDecimal(safe(parts, 3));
            Transaction t = new Transaction(investmentId, type, value, date);
            transactionsByInvestment.computeIfAbsent(investmentId, k -> new ArrayList<>()).add(t);
        }

        // sort transaction lists by date in ascending order
        for (List<Transaction> lst : transactionsByInvestment.values()) {
            lst.sort(Comparator.comparing(o -> o.date));
        }
    }
}

private static String safe(String[] arr, int idx) {
    if (idx < arr.length) return arr[idx].trim();
    return "";
}

/**
 * Compute portfolio for a specific investor and date:
 * - iterate all investments and pick those where this investor has transactions
 * - for each such investment compute investor-specific value as of refDate
 *
 * Return map investmentId -> value
 * In other words, find investments that the given investor has any transactions for (<= refDate)
 */
private static Map<String, BigDecimal> computePortfolioForInvestorOnDate(String investorId, LocalDate refDate) {
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    List<Investment> investmentsList = investmentsByInvestor.get(investorId);

    // Compute for each investment
    for (Investment inv : investmentsList) {
        BigDecimal val = computeValueForInvestorInvestment(inv.investmentId, investorId, refDate, new HashSet<>());
        result.put(inv.investmentId, val);
    }

    return result;
}

/**
 * Compute the value that the *given investor* holds in the given investment as of refDate.
 * This method performs a set of calculations based on investment type:
 * - STOCK: sharesHeld (sum of Shares transactions) * latest price (<= refDate)
 * - REALESTATE: last Estate + last Building transaction values (absolute)
 * - FUND: percentageHeld * fundTotalValue; percentageHeld is sum of Percentage transactions by this investor for the fund (<= refDate)
 * <p>
 * fundTotalValue is computed as sum of values of investments that have fondsInvestor == fundId
 */
private static BigDecimal computeValueForInvestorInvestment(String investmentId, String investorId, LocalDate refDate, Set<String> currentVisitedFunds) {
    Investment inv = investments.get(investmentId);
    if (inv == null) return BigDecimal.ZERO;

    String type = inv.type == null ? "" : inv.type.trim().toLowerCase();

    switch (type) {
        case "fonds":
        case "fund":
        case "fondsinvest": // handle possible variations

            // find percentage the investor owns in this fund (<= refDate)
            BigDecimal percentage = sumTransactionValues(investmentId, "Percentage", refDate);
            if (percentage.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

            // cycle detection: if fund already in currentVisitedFunds, break cycle and return 0 to avoid infinite recursion
            if (currentVisitedFunds.contains(investmentId)) {
                System.err.println("Cycle detected when evaluating fund " + investmentId + " — breaking cycle (counting as 0)");
                return BigDecimal.ZERO;
            }
            currentVisitedFunds.add(investmentId);

            // fund value is sum of values of child investments (those that have fondsInvestor == this investmentId)
            BigDecimal fundTotal = BigDecimal.ZERO;
            for (Investment child : investments.values()) {

                if (investmentId.equals(child.fondsInvestor)) {
                    // compute full value of the child investment (as if owned entirely by the fund)
                    BigDecimal childValue = computeValueOfInvestmentAsFundHeld(child.investmentId, refDate, currentVisitedFunds);
                    fundTotal = fundTotal.add(childValue);
                }
            }

            currentVisitedFunds.remove(investmentId);

            // investor's holding is percentage/100 * fundTotal
            return fundTotal.multiply(percentage).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

        case "stock":
        case "aktie":
        case "shares":
            // number of shares investor holds: sum of "Shares" transactions for given investor and investment (<= refDate)
            BigDecimal shares = sumTransactionValues(investmentId, "Shares", refDate);
            if (shares.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

            // find price for ISIN
            if (inv.isin == null || inv.isin.isEmpty()) {
                return BigDecimal.ZERO;
            }

            BigDecimal latestISINPrice = latestISINPriceOnOrBefore(inv.isin, refDate);
            if (latestISINPrice == null) return BigDecimal.ZERO;

            return shares.multiply(latestISINPrice);

        case "realestate":
        case "real_estate":
        case "estate":
        case "immobilie":
        case "immobili":
        case "real":
        default:
            // sum-up last Estate and last Building transactions (absolute values)
            BigDecimal estateVal = lastTransactionValue(investmentId, "Estate", refDate);
            BigDecimal buildingVal = lastTransactionValue(investmentId, "Building", refDate);

            return estateVal.add(buildingVal);
    }
}

// compute the value of an investment assuming the fund owns it completely (i.e. full value of the investment)
private static BigDecimal computeValueOfInvestmentAsFundHeld(String investmentId, LocalDate refDate, Set<String> currentVisitedFunds) {
    Investment inv = investments.get(investmentId);
    if (inv == null) return BigDecimal.ZERO;

    String type = inv.type == null ? "" : inv.type.trim().toLowerCase();

    switch (type) {
        case "fonds":
        case "fund":
            // fund value = sum of its child investments, BUT no investor percentage applied !
            // first check for cyclical relationships

            if (currentVisitedFunds.contains(investmentId)) {
                System.err.println("Cycle detected when evaluating fund " + investmentId + " (fund-held) — breaking cycle (counting as 0)");
                return BigDecimal.ZERO;
            }

            currentVisitedFunds.add(investmentId);

            BigDecimal total = BigDecimal.ZERO;
            for (Investment child : investments.values()) {

                if (investmentId.equals(child.fondsInvestor)) {
                    total = total.add(computeValueOfInvestmentAsFundHeld(child.investmentId, refDate, currentVisitedFunds));
                }
            }

            currentVisitedFunds.remove(investmentId);

            return total;

        case "stock":
        case "aktie":
        case "shares":
            // Fund-held stock -> we need to know how many shares the fund holds.
            // BUT our Transactions.csv stores transactions by investor.
            // Therefore, it is assumed that investments listed with 'InvestorId' means the investments belong to some owner
            // However, there won't necessarily be transactions under investor==fundId for fund-held assets.
            // Since data is randomized, stock value for the investment will be calculated by:
            //   -> finding the last-known "Shares" transaction for the given fund-owner (transaction.investmentId == fundId),
            //   -> else zero.

            BigDecimal shares = sumTransactionValuesForAnyOwner(investmentId, "Shares", refDate);
            if (shares.compareTo(BigDecimal.ZERO) == 0 || inv.isin == null || inv.isin.isEmpty()) return BigDecimal.ZERO;

            BigDecimal price = latestISINPriceOnOrBefore(inv.isin, refDate);
            if (price == null) return BigDecimal.ZERO;

            return shares.multiply(price);

        default:
            // last Estate + Building value for any owner
            BigDecimal estateVal = lastTransactionValueForAnyOwner(investmentId, "Estate", refDate);
            BigDecimal buildingVal = lastTransactionValueForAnyOwner(investmentId, "Building", refDate);

            return estateVal.add(buildingVal);
    }
}

// find the sum of transaction values of a given type for a given  investor and investment for txType (<= refDate)
private static BigDecimal sumTransactionValues(String investmentId, String txType, LocalDate refDate) {
    List<Transaction> transactions = transactionsByInvestment.getOrDefault(investmentId, Collections.emptyList());
    BigDecimal sum = BigDecimal.ZERO;

    for (Transaction t : transactions) {

        if (!t.date.isAfter(refDate) && t.type.equalsIgnoreCase(txType)) {
            sum = sum.add(t.value);
        }
    }

    return sum;
}

// find the sum of transactions for any owner (i.e. for fund-held holdings)
private static BigDecimal sumTransactionValuesForAnyOwner(String investmentId, String txType, LocalDate refDate) {
    List<Transaction> transactions = transactionsByInvestment.getOrDefault(investmentId, Collections.emptyList());
    BigDecimal sum = BigDecimal.ZERO;

    for (Transaction t : transactions) {

        if (!t.date.isAfter(refDate) && t.type.equalsIgnoreCase(txType)) {
            sum = sum.add(t.value);
        }
    }

    return sum;
}

// find the last transaction value (single value) for a given investor and investment for txType (<= refDate)
private static BigDecimal lastTransactionValue(String investmentId, String txType, LocalDate refDate) {
    List<Transaction> transactions = transactionsByInvestment.getOrDefault(investmentId, Collections.emptyList());
    Transaction lastTransaction = null;

    for (Transaction t : transactions) {

        if (!t.date.isAfter(refDate) && t.type.equalsIgnoreCase(txType)) {
            lastTransaction = t;
        }
    }

    return lastTransaction == null ? BigDecimal.ZERO : lastTransaction.value;
}

// find the last transaction value for any owner
private static BigDecimal lastTransactionValueForAnyOwner(String investmentId, String txType, LocalDate refDate) {
    List<Transaction> transactions = transactionsByInvestment.getOrDefault(investmentId, Collections.emptyList());
    Transaction lastTransaction = null;
    for (Transaction t : transactions) {

        if (!t.date.isAfter(refDate) && t.type.equalsIgnoreCase(txType)) {
            if (lastTransaction == null || lastTransaction.date.isBefore(t.date)) {
                lastTransaction = t;
            }
        }
    }

    return lastTransaction == null ? BigDecimal.ZERO : lastTransaction.value;
}

// find the latest price for ISIN on or before refDate
private static BigDecimal latestISINPriceOnOrBefore(String isin, LocalDate refDate) {
    List<Quote> quotes = quotesByISIN.get(isin);
    if (quotes == null || quotes.isEmpty()) return null;

    Quote candidateQuote = null;
    for (Quote q : quotes) {

        if (!q.date.isAfter(refDate)) candidateQuote = q;
        else break; // list is sorted ascending
    }

    return candidateQuote == null ? null : candidateQuote.pricePerShare;
}
