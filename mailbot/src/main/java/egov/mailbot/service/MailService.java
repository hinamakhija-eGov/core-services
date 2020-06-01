package egov.mailbot.service;

import egov.mailbot.config.MainConfiguration;
import egov.mailbot.models.*;
import egov.mailbot.models.notification.EmailNotification;
import egov.mailbot.models.notification.EmailNotificationRequest;
import egov.mailbot.models.notification.InReplyTo;
import egov.mailbot.producer.Producer;
import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.search.FlagTerm;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
@Slf4j
public class MailService {

    @Autowired
    private List<AttachmentProcessor> attachmentProcessors;

    @Autowired
    private UserService userService;

    @Autowired
    private Producer producer;

    @Autowired
    private MainConfiguration mainConfiguration;

    private Map<Action, AttachmentProcessor> attachmentProcessorMap = new HashMap<>();

    private static String decodeName(String name) throws UnsupportedEncodingException {
        if (name == null || name.length() == 0) {
            return "unknown";
        }
        String ret = java.net.URLDecoder.decode(name, "UTF-8");

        // also check for a few other things in the string:
        ret = ret.replaceAll("=\\?utf-8\\?q\\?", "");
        ret = ret.replaceAll("\\?=", "");
        ret = ret.replaceAll("=20", " ");

        return ret;
    }

    private static int saveFile(File saveFile, Part part) throws IOException, MessagingException {

        BufferedOutputStream bos = new BufferedOutputStream(
                new FileOutputStream(saveFile));

        byte[] buff = new byte[2048];
        InputStream is = part.getInputStream();
        int ret = 0, count = 0;
        while ((ret = is.read(buff)) > 0) {
            bos.write(buff, 0, ret);
            count += ret;
        }
        bos.close();
        is.close();
        return count;
    }

    @PostConstruct
    public void init() {
        attachmentProcessors.forEach(val -> attachmentProcessorMap.put(val.action(), val));
    }

    public void processMails(Store store, MailProcessorConfig config) {

        List<Mapping> mappings = config.getMappings();
        Folder folder = null;
        try {
            // Get folder
            folder = store.getFolder("INBOX");
            folder.open(Folder.READ_WRITE);

            Path tempDirWithPrefix = null;
            try {
                // Get directory listing
                tempDirWithPrefix = Files.createTempDirectory("mailbot");
                Message[] messages = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));

                if (messages.length == 0)
                    log.info("No unread messages in inbox!");

                for (Message message : messages) {
                    Email email = new Email();
                    folder.setFlags(new Message[]{message}, new Flags(Flags.Flag.SEEN), true);
                    email.from = message.getFrom() == null ? null : ((InternetAddress) message.getFrom()[0]).getAddress();
                    email.subject = message.getSubject();

                    ApplicableMapping applicableMapping = processMail(email, config);
                    if (applicableMapping.getMapping() != null) {
                        loadEmail(email, message, tempDirWithPrefix.toFile());


                        EmailNotificationRequest notificationRequest =
                                getReplyMailNotification(applicableMapping.getRequestInfo(), message);
                        try {
                            AttachmentProcessor dataUploadProcessor = attachmentProcessorMap.get(applicableMapping.getMapping().getAction());

                            Path attachment = Paths.get(email.attachments.get(0).path);
                            dataUploadProcessor.processAttachments(notificationRequest,
                                    applicableMapping.getMapping()
                                    , attachment);
                            notificationRequest.getEmail().setBody(applicableMapping.getMapping().getSuccessResponse());
                        } catch (AttachmentProcessingException e) {
                            // send error mail
                            notificationRequest.getEmail().setBody(applicableMapping.getMapping().getErrorResponse());
                            folder.setFlags(new Message[]{message}, new Flags(Flags.Flag.SEEN), false);
                        }

                        producer.push(mainConfiguration.getNotificationMailTopic(),
                                notificationRequest.getEmail().getEmailTo().toString(), notificationRequest);

                    }
                }

            } catch (IOException e) {
                log.error("Unable to create temp directory!", e);
                throw new CustomException("PROCESS_FAILED", "Unable to create temp directory");
            } finally {
                if (tempDirWithPrefix != null) {
                    tempDirWithPrefix.toFile().delete();
                }
            }
        } catch (MessagingException e) {
            log.error("Unable to open inbox", e);
            throw new CustomException("PROCESS_FAILED", "Unable to open inbox");
        } finally {
            try {
                if (folder != null)
                    folder.close(true); // true tells the mail server to expunge deleted
                store.close();
            } catch (MessagingException ignore) {
            }
        }

    }

    private ApplicableMapping processMail(Email email, MailProcessorConfig config) {
        RequestInfo requestInfo = new RequestInfo();
        ApplicableMapping applicableMapping = new ApplicableMapping();
        for (Mapping mapping : config.getMappings()) {
            boolean from = false, subject = false;
            for (Filter filter : mapping.getFilters()) {
                if (filter.getFilterType() == FilterType.USER_ROLE) {
                    Map<String, User> userMap = userService.getUsers(filter.getValue(), config.getTenantIds());
                    User user = userMap.get(email.from.toLowerCase());
                    if (user != null) {
                        from = userMap.containsKey(email.from.toLowerCase());

                        if (requestInfo.getUserInfo() == null)
                            requestInfo.setUserInfo(user);
                    }
                }
                if (filter.getFilterType() == FilterType.SUBJECT) {
                    subject = filter.getValue().stream().anyMatch(v -> email.subject.toLowerCase().contains(v.toLowerCase()));
                }
            }
            if (from && subject) {
                applicableMapping.setMapping(mapping);
                applicableMapping.setRequestInfo(requestInfo);
                break;
            }
        }


        return applicableMapping;
    }

    private EmailNotificationRequest getReplyMailNotification(RequestInfo requestInfo, Message message) throws MessagingException {
        Message replyMsg = message.reply(false);
        Address[] toArray = replyMsg.getRecipients(Message.RecipientType.TO);
        Set<String> toAddress = new HashSet<>();
        for (Address to : toArray)
            toAddress.add(((InternetAddress) to).getAddress());

        String[] messageIdHeader = replyMsg.getHeader("In-Reply-To");
        String[] referencesHeader = replyMsg.getHeader("References");

        InReplyTo inReplyTo = InReplyTo.builder()
                .messageId(messageIdHeader != null ? messageIdHeader[0]: null)
                .references(referencesHeader != null? referencesHeader[0]: null)
                .build();
        EmailNotification emailNotification = EmailNotification.builder()
                .emailTo(toAddress)
                .subject(replyMsg.getSubject())
                .inReplyTo(inReplyTo)
                .build();

        return new EmailNotificationRequest(requestInfo, emailNotification);

    }

    private void loadEmail(Email email, Message message, File directory) {
        try {

            // to list
            Address[] toArray = message.getRecipients(Message.RecipientType.TO);
            for (Address to : toArray)
                email.to.add(((InternetAddress) to).getAddress());

            // received date
            if (message.getReceivedDate() != null) {
                email.received = message.getReceivedDate();
            } else {
                email.received = new Date();
            }

            Object content = message.getContent();
            if (content instanceof Multipart) {
                Multipart mp = (Multipart) content;

                for (int j = 0; j < mp.getCount(); j++) {

                    Part part = mp.getBodyPart(j);
                    String disposition = part.getDisposition();

                    if (disposition == null || Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
                        MimeBodyPart mbp = (MimeBodyPart) part;
                        if (mbp.isMimeType("text/csv") || mbp.isMimeType("application/octet-stream") || mbp.isMimeType("application/vnd.ms-excel")) {
                            String filename = decodeName(part.getFileName());

                            if (filename.toLowerCase().endsWith(".csv")) {
                                EmailAttachment attachment = new EmailAttachment();
                                attachment.name = filename;
                                File file = new File(directory, attachment.name);
                                attachment.path = file.getAbsolutePath();
                                attachment.size = saveFile(file, part);
                                email.attachments.add(attachment);

                                // Process only one CSV per mail
                                break;
                            }
                        }
                    }
                } // end of multipart for loop
            } // end messages for loop

        } catch (IOException | MessagingException e) {
            e.printStackTrace();
            throw new CustomException("ERROR", "Error while fetching mail");
        }
    }
}
