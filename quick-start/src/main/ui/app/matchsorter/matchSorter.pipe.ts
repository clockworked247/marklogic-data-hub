import { Pipe, PipeTransform } from '@angular/core';
import * as _ from 'lodash';

@Pipe({
    name: 'matchSorter',
})
export class MatchSorterPipe implements PipeTransform { 
    transform(input: any[] = [], options: Array|string, value: string): any {
      let filteredInput = input;
      value = String(value).toLowerCase();  // normalzied to lowercase

      if (value.length)
        filteredInput = _.filter(input, function(i) { 
          let found = false;
          _.forEach(options, (field) => {
            let fieldVal = String(i[field]).toLowerCase();
            if (fieldVal.indexOf(value) !== -1 ) {
              found = true;
              return false; // forEach break loop
            }
          })
          return found; 
        });

      filteredInput = _.sortBy(filteredInput, options);
      
      return filteredInput;
    }
}