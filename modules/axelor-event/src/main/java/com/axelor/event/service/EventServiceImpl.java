package com.axelor.event.service;

import com.axelor.apps.message.db.EmailAccount;
import com.axelor.apps.message.db.Message;
import com.axelor.apps.message.db.Template;
import com.axelor.apps.message.db.repo.EmailAccountRepository;
import com.axelor.apps.message.db.repo.TemplateRepository;
import com.axelor.apps.message.service.MessageService;
import com.axelor.apps.message.service.TemplateMessageService;
import com.axelor.auth.AuthUtils;
import com.axelor.common.FileUtils;
import com.axelor.data.ImportTask;
import com.axelor.data.csv.CSVImporter;
import com.axelor.dms.db.DMSFile;
import com.axelor.dms.db.repo.DMSFileRepository;
import com.axelor.event.db.Discount;
import com.axelor.event.db.Event;
import com.axelor.event.db.EventRegistration;
import com.axelor.event.db.repo.EventRegistrationRepository;
import com.axelor.event.db.repo.EventRepository;
import com.axelor.exception.AxelorException;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.db.repo.MetaFileRepository;
import com.google.common.io.Files;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventServiceImpl implements EventService {

  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Inject MessageService messageService;

  @Inject TemplateMessageService templateService;

  @Inject MetaFileRepository metaFileRepository;

  @Inject TemplateRepository templateRepository;

  @Inject EventRegistrationRepository eventRegistrationRepository;

  @Override
  public Event computeTotal(Event event) {

    Integer totalEntry = 0;
    BigDecimal totalCollection = BigDecimal.ZERO;
    BigDecimal totalDiscount = BigDecimal.ZERO;
    BigDecimal totalFees = BigDecimal.ZERO;

    List<EventRegistration> eventRegistrationList = event.getEventRegistrationList();

    if (eventRegistrationList != null) {
      totalEntry = eventRegistrationList.size();
      totalCollection =
          eventRegistrationList
              .stream()
              .map(x -> x.getAmount())
              .reduce(BigDecimal.ZERO, BigDecimal::add);

      totalFees = event.getEventFees().multiply(new BigDecimal(totalEntry));

      if (totalFees.compareTo(totalCollection) > 0) {

        totalDiscount =
            event.getEventFees().multiply(new BigDecimal(totalEntry)).subtract(totalCollection);
      } else {
        totalDiscount = BigDecimal.ZERO;
      }

      event.setAmountCollected(totalCollection);
      event.setTotalDiscount(totalDiscount);
      event.setTotalEntry(eventRegistrationList.size());
    }
    return event;
  }

  @Override
  public Event verifyEvent(Event event) {

    if (event.getDiscountList() != null) {
      List<Discount> discountList = event.getDiscountList();
      List<Discount> updatedDiscountList = new ArrayList<>();

      for (Discount discount : discountList) {
        discount.setDiscountAmount(
            event
                .getEventFees()
                .multiply(discount.getDiscountPercent())
                .divide(new BigDecimal(100)));
        updatedDiscountList.add(discount);
      }
      event.setDiscountList(updatedDiscountList);
    }

    event = computeTotal(event);
    return event;
  }

  public void importCsvInEventRegistration(MetaFile metaFile, Integer event_id) {

    File configFile = this.getConfigFile();
    File csvFile = this.getDataCsvFile(metaFile);

    CSVImporter importer = new CSVImporter(configFile.getAbsolutePath());
    Map<String, Object> context = new HashMap<String, Object>();
    context.put("event_id", event_id.longValue());
    Event event = Beans.get(EventRepository.class).find(Long.parseLong(event_id.toString()));
    context.put("reg_list_size", event.getEventRegistrationList().size());

    importer.setContext(context);

    importer.run(
        new ImportTask() {
          @Override
          public void configure() throws IOException {
            input("[event_registration]", csvFile);
          }
        });

    deleteTempFile(configFile);
    deleteTempFile(csvFile);

    this.removeMetaFile(metaFile);
    // metaFileRepository.remove(metaFile);
  }

  @Transactional
  public void removeMetaFile(MetaFile metaFile) {
    metaFileRepository.remove(metaFile);
  }

  private void deleteTempFile(File file) {
    try {
      if (file.isDirectory()) {
        FileUtils.deleteDirectory(file);
      } else {
        file.delete();
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public File getConfigFile() {

    File configFile = null;
    try {
      configFile = File.createTempFile("input-config", ".xml");
      InputStream is = this.getClass().getResourceAsStream("/import-configs/input-config.xml");
      FileOutputStream os = new FileOutputStream(configFile);
      IOUtils.copy(is, os);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return configFile;
  }

  public File getDataCsvFile(MetaFile dataFile) {

    File csvFile = null;
    try {
      File tempDir = Files.createTempDir();
      csvFile = new File(tempDir, "eventRegistration.csv");
      Files.copy(MetaFiles.getPath(dataFile).toFile(), csvFile);

    } catch (Exception e) {
      e.printStackTrace();
    }
    return csvFile;
  }

  @Transactional
  @Override
  public void sendEmail(Event event) {

    Template template = templateRepository.findByName("event");
    EmailAccount emailAccount = Beans.get(EmailAccountRepository.class).all().fetchOne();

    if (template != null) {
      List<EventRegistration> eventRegistrationList = event.getEventRegistrationList();
      if (eventRegistrationList != null && !eventRegistrationList.isEmpty()) {

        List<EventRegistration> eventRegistrations = event.getEventRegistrationList();
        for (EventRegistration eventRegistration : eventRegistrations) {
          if (eventRegistration.getEmail() != null) {
            String toRecipents = eventRegistration.getEmail().toString();
            Message message = null;
            template.setToRecipients(toRecipents);
            try {
              message =
                  templateService.generateMessage(
                      Long.parseLong(eventRegistration.getId().toString()),
                      "com.axelor.event.db.EventRegistration",
                      "EventRegistration",
                      template);
            } catch (NumberFormatException
                | ClassNotFoundException
                | InstantiationException
                | IllegalAccessException
                | AxelorException
                | IOException e1) {
              // TODO Auto-generated catch block
              e1.printStackTrace();
            }
            message.setSentDateT(LocalDateTime.now());
            message.setSenderUser(AuthUtils.getUser());
            message.setMailAccount(emailAccount);

            List<DMSFile> dmfiles =
                Beans.get(DMSFileRepository.class)
                    .all()
                    .filter("self.relatedId = ?", event.getId())
                    .fetch();

            Set<MetaFile> metaFiles = new HashSet<MetaFile>();

            if (dmfiles != null) {
              for (DMSFile file : dmfiles) {
                // System.out.println(file.getFileName());
                MetaFile metaFile = file.getMetaFile();
                if (metaFile != null) {
                  metaFiles.add(metaFile);
                }
              }
            }

            messageService.attachMetaFiles(message, metaFiles);

            if (message == null) {
              log.debug("Message Is Null ::: {}");

            } else {
              try {
                messageService.sendMessage(message);
              } catch (AxelorException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
            }
          }
        }
      }
    } else {
      System.err.println("Template Not Found Please set Template");
    }
  }
}
