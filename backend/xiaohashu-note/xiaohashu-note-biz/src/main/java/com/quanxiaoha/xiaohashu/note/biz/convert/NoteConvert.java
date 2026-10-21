package com.quanxiaoha.xiaohashu.note.biz.convert;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.PublishNoteDTO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * Note domain conversion methods.
 */
@Mapper
public interface NoteConvert {

    NoteConvert INSTANCE = Mappers.getMapper(NoteConvert.class);

    PublishNoteDTO convertDO2DTO(NoteDO bean);

    NoteDO convertDTO2DO(PublishNoteDTO bean);
}
