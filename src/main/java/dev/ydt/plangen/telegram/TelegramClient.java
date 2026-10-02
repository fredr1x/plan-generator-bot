package dev.ydt.plangen.telegram;

import dev.ydt.plangen.telegram.Dto.AnswerCallback;
import dev.ydt.plangen.telegram.Dto.DeleteMessage;
import dev.ydt.plangen.telegram.Dto.EditMessage;
import dev.ydt.plangen.telegram.Dto.FileResult;
import dev.ydt.plangen.telegram.Dto.GetFile;
import dev.ydt.plangen.telegram.Dto.SendMessage;
import dev.ydt.plangen.telegram.Dto.SendResult;
import dev.ydt.plangen.telegram.Dto.SetMyCommands;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.File;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;

@RegisterRestClient(configKey = "telegram")
@Produces(MediaType.APPLICATION_JSON)
public interface TelegramClient {

    @POST
    @Path("sendMessage")
    @Consumes(MediaType.APPLICATION_JSON)
    SendResult sendMessage(SendMessage body);

    @POST
    @Path("editMessageText")
    @Consumes(MediaType.APPLICATION_JSON)
    void editMessageText(EditMessage body);

    @POST
    @Path("deleteMessage")
    @Consumes(MediaType.APPLICATION_JSON)
    void deleteMessage(DeleteMessage body);

    @POST
    @Path("answerCallbackQuery")
    @Consumes(MediaType.APPLICATION_JSON)
    void answerCallbackQuery(AnswerCallback body);

    @POST
    @Path("getFile")
    @Consumes(MediaType.APPLICATION_JSON)
    FileResult getFile(GetFile body);

    @POST
    @Path("setMyCommands")
    @Consumes(MediaType.APPLICATION_JSON)
    void setMyCommands(SetMyCommands body);

    @POST
    @Path("sendDocument")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    void sendDocument(
            @RestForm("chat_id") String chatId,
            @RestForm("caption") String caption,
            @RestForm("document") @PartType(MediaType.APPLICATION_OCTET_STREAM) File document);
}
