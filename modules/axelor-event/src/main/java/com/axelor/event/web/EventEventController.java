package com.axelor.event.web;

import com.axelor.event.db.Event;
import com.axelor.event.service.EventService;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.db.repo.MetaFileRepository;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.util.LinkedHashMap;

public class EventEventController {

  public void computeTotal(ActionRequest request, ActionResponse response) {
    Event event = request.getContext().asType(Event.class);
    event = Beans.get(EventService.class).computeTotal(event);
    response.setValues(event);
  }

  public void verifyEvent(ActionRequest request, ActionResponse response) {
    Event event = request.getContext().asType(Event.class);
    event = Beans.get(EventService.class).verifyEvent(event);

    response.setValue("discountList", event.getDiscountList());
    response.setValue("eventRegistrationList", event.getEventRegistrationList());

    response.setValue("totalEntry", event.getTotalEntry());
    response.setValue("amountCollected", event.getAmountCollected());
    response.setValue("totalDiscount", event.getTotalDiscount());

    // response.setValues(event);
  }

  @SuppressWarnings("unchecked")
  public void importCsvEventRegistration(ActionRequest request, ActionResponse response) {

    Integer event_id = (Integer) request.getContext().get("_id");

    LinkedHashMap<String, Object> map =
        (LinkedHashMap<String, Object>) request.getContext().get("metaFile");

    if (map.get("fileType").toString().equals("text/csv")) {
      MetaFile metaFile =
          Beans.get(MetaFileRepository.class).find(Long.parseLong(map.get("id").toString()));
      Beans.get(EventService.class).importCsvInEventRegistration(metaFile, event_id);
    } else {
      response.setError("Invalid File Type");
    }

    response.setNotify("Data Imported");
    response.setCanClose(true);
  }

  public void emailSend(ActionRequest request, ActionResponse response) {
    Event event = request.getContext().asType(Event.class);
    Beans.get(EventService.class).sendEmail(event);

    response.setFlash("Email Send Successful");
    response.setReload(true);
  }
}
