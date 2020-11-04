package com.axelor.mail.service;

import java.util.Map;

import com.axelor.inject.Beans;
import com.axelor.mail.db.Candidate;
import com.axelor.mail.db.MailSender;
import com.axelor.mail.db.repo.MailSenderRepository;

public class SenderCSVImportServiceImpl {

	public Object importRegistrationData(Object bean, Map<String, Object> values) {

		assert bean instanceof Candidate;
		Candidate candidate = (Candidate) bean;
		MailSender mailSender = (Beans.get(MailSenderRepository.class)
				.find(Long.parseLong(values.get("sender_id").toString())));
		candidate.setMailSender(mailSender);
		return candidate;
	}
}
