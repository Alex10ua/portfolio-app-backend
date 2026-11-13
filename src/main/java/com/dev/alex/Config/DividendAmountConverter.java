package com.dev.alex.Config;

import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.convert.MongoConversionContext;
import org.springframework.data.mongodb.core.convert.MongoValueConverter;

import java.math.BigDecimal;

/** Keeps a missing provider amount from making the entire market-data document unreadable. */
public class DividendAmountConverter implements MongoValueConverter<BigDecimal, Object> {
    @Override
    public BigDecimal read(Object value, MongoConversionContext context) {
        if (value instanceof Double number && !Double.isFinite(number)) return null;
        if (value instanceof Float number && !Float.isFinite(number)) return null;
        if (value instanceof Decimal128 number) return number.isFinite() ? number.bigDecimalValue() : null;
        return new BigDecimal(value.toString());
    }

    @Override
    public Object write(BigDecimal value, MongoConversionContext context) {
        return context.write(value);
    }
}
