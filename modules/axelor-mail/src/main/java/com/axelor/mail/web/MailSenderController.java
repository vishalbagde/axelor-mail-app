package com.axelor.mail.web;

import com.axelor.apps.message.db.EmailAccount;
import com.axelor.apps.message.db.Message;
import com.axelor.apps.message.db.Template;
import com.axelor.apps.message.db.repo.EmailAccountRepository;
import com.axelor.apps.message.db.repo.TemplateRepository;
import com.axelor.apps.message.service.MessageService;
import com.axelor.apps.message.service.TemplateMessageService;
import com.axelor.apps.message.service.TemplateService;
import com.axelor.auth.AuthUtils;
import com.axelor.data.ImportTask;
import com.axelor.data.csv.CSVImporter;
import com.axelor.dms.db.DMSFile;
import com.axelor.dms.db.repo.DMSFileRepository;

import com.axelor.exception.AxelorException;
import com.axelor.inject.Beans;
import com.axelor.mail.db.Candidate;
import com.axelor.mail.db.MailSender;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.db.repo.MetaFileRepository;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.common.io.Files;
import com.google.inject.Inject;

import net.minidev.json.writer.BeansMapper.Bean;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MailSenderController {
	
	private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	@Inject
	TemplateMessageService templateMessageService;

	@Inject
	MessageService messageService;

	public void importCsvFile(MetaFile metaFile, Integer event_id) {
		File configFile = this.getConfigFile();
		File csvFile = this.getDataCsvFile(metaFile);

		CSVImporter importer = new CSVImporter(configFile.getAbsolutePath());

		Map<String, Object> context = new HashMap<String, Object>();
		context.put("sender_id", event_id.longValue());
		importer.setContext(context);

		importer.run(new ImportTask() {
			@Override
			public void configure() throws IOException {
				input("[candidate_registration]", csvFile);
			}
		});
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

	public void importCsvCandidate(ActionRequest request, ActionResponse response) {

		Integer sender_id = (Integer) request.getContext().get("_id");
		LinkedHashMap<String, Object> map = (LinkedHashMap<String, Object>) request.getContext().get("metaFile");
		System.out.println(map.get("fileType").toString());
		MetaFile metaFile = Beans.get(MetaFileRepository.class).find(Long.parseLong(map.get("id").toString()));
		System.out.println(metaFile.getFileName() + " " + metaFile.getFileType());
		importCsvFile(metaFile, sender_id);
		response.setReload(true);
	}

	public void emailSend(ActionRequest request, ActionResponse response) {

		String model = request.getModel();

		System.out.println("email send call");

		MailSender mailSender = request.getContext().asType(MailSender.class);

		String candidateModel="com.axelor.mail.db.Candidate";
		Template template = Beans.get(TemplateRepository.class).all().filter("self.metaModel.fullName = ?",candidateModel).fetchOne();
		
		EmailAccount emailAccount = Beans.get(EmailAccountRepository.class).all().fetchOne();

		if (template != null) {
			List<Candidate> candidateList = mailSender.getCandidateList();
			if (candidateList != null && !candidateList.isEmpty()) {

				for (Candidate candidate : candidateList) {
					if (candidate.getEmail() != null) {
						String toRecipents = candidate.getEmail().toString();
						Message message = null;
						template.setToRecipients(toRecipents);
						template.setCcRecipients(candidate.getCc());
						try {
							message = templateMessageService.generateMessage(candidate.getId(),
									candidateModel, candidate.getClass().toString(), template);
						} catch (NumberFormatException | ClassNotFoundException | InstantiationException
								| IllegalAccessException | AxelorException | IOException e1) {
							// TODO Auto-generated catch block
							e1.printStackTrace();
						}
						message.setSentDateT(LocalDateTime.now());
						message.setSenderUser(AuthUtils.getUser());
						message.setMailAccount(emailAccount);

						List<DMSFile> dmfiles = Beans.get(DMSFileRepository.class).all()
								.filter("self.relatedId = ?", mailSender.getId()).fetch();

						Set<MetaFile> metaFiles = new HashSet<MetaFile>();

						if (dmfiles != null) {
							for (DMSFile file : dmfiles) {
								// System.out.println(file.getFileName());
								MetaFile metaFile = file.getMetaFile();
								if (metaFile != null) {
									if (metaFile.getFileName().equals(candidate.getAttachment())) {
										metaFiles.add(metaFile);
									}
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
