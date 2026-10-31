package com.dev.alex.Service.Interface;

import com.dev.alex.Model.RealEstateProperty;
import com.dev.alex.Service.RealEstateServiceImpl.PropertyView;
import java.time.LocalDate;
import java.util.List;

public interface RealEstateService {
    List<PropertyView> viewsFor(String portfolioId, LocalDate asOf);
    RealEstateProperty requireProperty(String portfolioId, String propertyId);
}
