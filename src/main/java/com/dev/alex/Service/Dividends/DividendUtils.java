package com.dev.alex.Service.Dividends;

import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.Transactions;
import jakarta.validation.constraints.Null;
import org.springframework.context.annotation.Description;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;



public class DividendUtils {

    private static final BigDecimal ZERO = BigDecimal.valueOf(0);

    @Deprecated(since = "After implementing  auto import of div transaction")
    public BigDecimal calculateAllDividendsByStockAuto(List<Dividend> dividendList, List<Transactions> transactionsList, List<Splits> splitsList){
        BigDecimal totalAmount = ZERO;
        int compareResult;
        //get div date and check all transaction how much stock is present at that time
        for (Dividend dividend:dividendList){
            BigDecimal totalStock = ZERO;
            LocalDate dividendDate = dividend.getDividendDate();
            for (Transactions transaction:transactionsList){
                if(transaction.getTransactionType().equals(TransactionType.BUY) && transaction.getDate().isBefore(dividendDate)){
                    if (splitsList != null){
                        for (Splits split:splitsList){
                            if (split.getSplitDate().isAfter(transaction.getDate()) && split.getSplitDate().isBefore(dividendDate)){
                                compareResult = totalStock.compareTo(ZERO);
                                if (compareResult == 0 ) {
                                    totalStock = totalStock.add(transaction.getQuantity().multiply(split.getRatioSplit()));
                                } else {
                                    totalStock = totalStock.multiply(split.getRatioSplit());
                                }
                            }
                        }
                        compareResult = totalStock.compareTo(ZERO);
                        if (compareResult == 0) {
                            totalStock = totalStock.add(transaction.getQuantity());
                        }
                    } else {
                        totalStock = totalStock.add(transaction.getQuantity());
                    }
                } else if (transaction.getTransactionType().equals(TransactionType.SELL) && transaction.getDate().isBefore(dividendDate)) {
                    if(totalStock.equals(ZERO)){
                        throw new IllegalArgumentException("More sell than buy");
                    }
                    if (splitsList != null){
                        for (Splits split:splitsList){
                            if (split.getSplitDate().isAfter(transaction.getDate()) && split.getSplitDate().isBefore(dividendDate)){
                                compareResult = totalStock.compareTo(ZERO);
                                if (compareResult == 0) {
                                    totalStock = totalStock.subtract(transaction.getQuantity().multiply(split.getRatioSplit()));
                                } else {
                                    totalStock = totalStock.subtract(totalStock.multiply(split.getRatioSplit()));
                                }
                            }
                        }
                        compareResult = totalStock.compareTo(ZERO);
                        if (compareResult == 0){
                            totalStock = totalStock.subtract(transaction.getQuantity());
                        }
                    } else {
                        totalStock = totalStock.subtract(transaction.getQuantity());
                    }
                }
            }
            compareResult = totalStock.compareTo(ZERO);
            if (compareResult > 0){
                totalAmount = totalAmount.add(totalStock.multiply(dividend.getDividendAmount()).setScale(2, RoundingMode.HALF_EVEN));
            }
        }

        return totalAmount;
    }

    public BigDecimal calculateAllDividendsByStock(List<Transactions> transactionsList){
        BigDecimal totalAmount = new BigDecimal(0);
        for (Transactions transaction : transactionsList){
            totalAmount = totalAmount.add(transaction.getTotalAmount());
        }
        return totalAmount;
    }
    @Deprecated
    @Description("Change to calculateDividendsPerMonth after div transaction implementation")
    public Map<String, BigDecimal> calculateDividendsPerMonthAuto(Map<String, List<Transactions>> transactionsList, Map<String, List<Dividend>> dividendLists, Map<String, List<Splits>> splitsLists, List<String> holdings){
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM");
        Map<String, BigDecimal> monthDividendsMap = new HashMap<>();
        int compareResult;
        for (String holding:holdings){
           List<Dividend> dividendList = dividendLists.get(holding);
           List<Transactions> transactionList = transactionsList.get(holding);
           List<Splits> splitsList = splitsLists.get(holding);
           for (Dividend dividend:dividendList){
                LocalDate dividendDate = dividend.getDividendDate();
                BigDecimal totalStock = ZERO;
                BigDecimal divAmount;
                for (Transactions transaction:transactionList){
                    if (transaction.getTransactionType().equals(TransactionType.BUY) && transaction.getDate().isBefore(dividendDate)){
                        if (splitsList != null) {
                           for (Splits split : splitsList) {
                                if (split.getSplitDate().isAfter(transaction.getDate()) && split.getSplitDate().isBefore(dividendDate)){
                                    compareResult = totalStock.compareTo(ZERO);
                                    if (compareResult == 0){
                                        totalStock = totalStock.add(transaction.getQuantity().multiply(split.getRatioSplit()));
                                    }else {
                                        totalStock = totalStock.multiply(split.getRatioSplit());
                                    }
                                }
                            }
                           compareResult = totalStock.compareTo(ZERO);
                           if (compareResult == 0){
                               totalStock = totalStock.add(transaction.getQuantity());
                           }
                        }else {
                            totalStock = totalStock.add(transaction.getQuantity());
                        }
                    } else if (transaction.getTransactionType().equals(TransactionType.SELL) && transaction.getDate().isBefore(dividendDate)) {
                        if(totalStock.equals(ZERO)){
                            throw new IllegalArgumentException("More sell than buy");
                        }
                        if (splitsList != null){
                            for (Splits split:splitsList){
                                if (split.getSplitDate().isAfter(transaction.getDate()) && split.getSplitDate().isBefore(dividendDate)){
                                    compareResult = totalStock.compareTo(ZERO);
                                    if (compareResult == 0){
                                        totalStock = totalStock.subtract(transaction.getQuantity().multiply(split.getRatioSplit()));
                                    }else {
                                        totalStock = totalStock.subtract(totalStock.multiply(split.getRatioSplit()));
                                    }
                                }
                            }
                            compareResult = totalStock.compareTo(ZERO);
                            if (compareResult == 0) {
                                totalStock = totalStock.subtract(transaction.getQuantity());
                            }
                        } else {
                            totalStock = totalStock.subtract(transaction.getQuantity());
                        }
                    }
                }
               //System.out.println(holding+" div date:" + dividendDate + " stock present: "+ totalStock);
               compareResult = totalStock.compareTo(ZERO);
               if (compareResult > 0){
                    divAmount = totalStock.multiply(dividend.getDividendAmount());
                    String formattedDateStr = dividendDate.format(formatter);
                     // Add to the map
                    monthDividendsMap.merge(formattedDateStr, divAmount, BigDecimal::add);
               }

           }
       }
        return monthDividendsMap;
    }

    public Map<Date, BigDecimal> calculateDividendsPerMonth(List<Transactions> transactionsList){
        Map<Date, BigDecimal> monthDividendsMap = new HashMap<>();
        SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy-MM");
        for (Transactions transaction:transactionsList){
            String formattedDateStr = outputFormat.format(transaction.getDate());
            Date formattedDate = null;
            try {
                formattedDate = outputFormat.parse(formattedDateStr);
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
            monthDividendsMap.merge(formattedDate, transaction.getAmount(), BigDecimal::add);
        }
        return monthDividendsMap;
    }

}
